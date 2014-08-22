/*
 * Copyright 2012-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.trace;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.config.PointcutConfig;
import org.glowroot.dynamicadvice.DynamicAdviceGenerator;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.markers.ThreadSafe;
import org.glowroot.weaving.Advice;
import org.glowroot.weaving.ClassLoaders;
import org.glowroot.weaving.LazyDefinedClass;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class AdviceCache {

    private static final AtomicInteger jarFileCounter = new AtomicInteger();

    private final ImmutableList<Advice> pluginAdvisors;
    @Nullable
    private final Instrumentation instrumentation;
    private final File dataDir;

    private volatile ImmutableList<Advice> reweavableAdvisors;
    private volatile ImmutableSet<String> reweavablePointcutConfigVersions;

    private volatile ImmutableList<Advice> allAdvisors;

    AdviceCache(ImmutableList<Advice> pluginAdvisors,
            ImmutableList<PointcutConfig> reweavablePointcutConfigs,
            @Nullable Instrumentation instrumentation, File dataDir) throws IOException {
        this.pluginAdvisors = pluginAdvisors;
        this.instrumentation = instrumentation;
        this.dataDir = dataDir;
        updateAdvisors(reweavablePointcutConfigs, true);
    }

    Supplier<ImmutableList<Advice>> getAdvisorsSupplier() {
        return new Supplier<ImmutableList<Advice>>() {
            @Override
            public ImmutableList<Advice> get() {
                return allAdvisors;
            }
        };
    }

    @EnsuresNonNull({"reweavableAdvisors", "reweavablePointcutConfigVersions", "allAdvisors"})
    public void updateAdvisors(/*>>>@org.checkerframework.checker.initialization.qual.UnknownInitialization(AdviceCache.class) AdviceCache this,*/
            ImmutableList<PointcutConfig> reweavablePointcutConfigs, boolean cleanTmpDir)
            throws IOException {
        ImmutableMap<Advice, LazyDefinedClass> advisors =
                DynamicAdviceGenerator.createAdvisors(reweavablePointcutConfigs, null);
        if (instrumentation == null) {
            // this is for tests that don't run with javaagent container
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) {
                throw new AssertionError("Context class loader must be set");
            }
            ClassLoaders.defineClassesInClassLoader(advisors.values(), loader);
        } else {
            File generatedJarDir = new File(dataDir, "tmp");
            if (cleanTmpDir) {
                ClassLoaders.cleanPreviouslyGeneratedJars(generatedJarDir, "config-pointcuts");
            }
            if (!advisors.isEmpty()) {
                String suffix = "";
                int count = jarFileCounter.incrementAndGet();
                if (count > 1) {
                    suffix = "-" + count;
                }
                File jarFile = new File(generatedJarDir, "config-pointcuts" + suffix + ".jar");
                ClassLoaders.defineClassesInBootstrapClassLoader(advisors.values(),
                        instrumentation, jarFile);
            }
        }
        reweavableAdvisors = advisors.keySet().asList();
        reweavablePointcutConfigVersions =
                createReweavablePointcutConfigVersions(reweavablePointcutConfigs);
        allAdvisors = ImmutableList.copyOf(Iterables.concat(pluginAdvisors, reweavableAdvisors));
    }

    public boolean isOutOfSync(ImmutableList<PointcutConfig> reweavablePointcutConfigs) {
        Set<String> versions = Sets.newHashSet();
        for (PointcutConfig reweavablePointcutConfig : reweavablePointcutConfigs) {
            versions.add(reweavablePointcutConfig.getVersion());
        }
        return !versions.equals(this.reweavablePointcutConfigVersions);
    }

    private static ImmutableSet<String> createReweavablePointcutConfigVersions(
            List<PointcutConfig> reweavablePointcutConfigs) {
        Set<String> versions = Sets.newHashSet();
        for (PointcutConfig reweavablePointcutConfig : reweavablePointcutConfigs) {
            versions.add(reweavablePointcutConfig.getVersion());
        }
        return ImmutableSet.copyOf(versions);
    }

    // this method exists because tests cannot use (sometimes) shaded guava Supplier
    @OnlyUsedByTests
    public List<Advice> getAdvisors() {
        return getAdvisorsSupplier().get();
    }
}
