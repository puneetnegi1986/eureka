/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.eureka2.server;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import com.codahale.metrics.JmxReporter;
import com.codahale.metrics.MetricRegistry;
import com.google.inject.AbstractModule;
import com.google.inject.Injector;
import com.netflix.config.ConfigurationManager;
import com.netflix.eureka2.server.config.EurekaCommonConfig;
import com.netflix.eureka2.server.health.EurekaHealthStatusModule;
import com.netflix.eureka2.server.health.KaryonHealthCheckHandler;
import com.netflix.eureka2.server.utils.guice.PostInjectorModule;
import com.netflix.governator.configuration.ArchaiusConfigurationProvider;
import com.netflix.governator.configuration.ArchaiusConfigurationProvider.Builder;
import com.netflix.governator.configuration.ConfigurationOwnershipPolicies;
import com.netflix.governator.guice.BootstrapBinder;
import com.netflix.governator.guice.BootstrapModule;
import com.netflix.governator.guice.LifecycleInjector;
import com.netflix.governator.guice.LifecycleInjectorBuilder;
import com.netflix.governator.lifecycle.LifecycleManager;
import com.netflix.governator.lifecycle.LifecycleState;
import com.netflix.spectator.api.Clock;
import com.netflix.spectator.api.ExtendedRegistry;
import com.netflix.spectator.metrics3.MetricsRegistry;
import netflix.adminresources.resources.KaryonWebAdminModule;
import netflix.karyon.archaius.DefaultPropertiesLoader;
import netflix.karyon.archaius.PropertiesLoader;
import netflix.karyon.health.HealthCheckHandler;
import netflix.karyon.health.HealthCheckInvocationStrategy;
import netflix.karyon.health.SyncHealthCheckInvocationStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Tomasz Bak
 */
public abstract class AbstractEurekaServer<C extends EurekaCommonConfig> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractEurekaServer.class);

    protected final C config;
    protected final String name;

    protected Injector injector;
    private LifecycleManager lifecycleManager;

    /**
     * A latch released by {@link ShutdownDetector}, to determine when container shutdown started.
     */
    private final CountDownLatch injectorShutdown = new CountDownLatch(1);

    protected AbstractEurekaServer(String name) {
        this.config = null;
        this.name = name;
    }

    protected AbstractEurekaServer(C config) {
        this.config = config;
        this.name = null;
    }

    public int getShutdownPort() {
        EurekaShutdownService shutdownService = injector.getInstance(EurekaShutdownService.class);
        if (shutdownService != null) {
            return shutdownService.getShutdownPort();
        }
        return -1;
    }

    public void start() throws Exception {
        List<BootstrapModule> bootstrapModules = new ArrayList<>();
        bootstrapModules.add(new BootstrapModule() {
            @Override
            public void configure(BootstrapBinder binder) {
                if (config == null) {
                    bindConfigurationProvider(binder);
                } else {
                    // WebAdmin resource uses Archaius singleton
                    ConfigurationManager.getConfigInstance().setProperty(
                            "netflix.platform.admin.resources.port", Integer.toString(config.getWebAdminPort()));
                }

                bindMetricsRegistry(binder);
                binder.include(EurekaHealthStatusModule.class);
                binder.include(KaryonWebAdminModule.class);
                binder.include(new AbstractModule() {
                    @Override
                    protected void configure() {
                        bind(EurekaShutdownService.class).asEagerSingleton();
                        bind(ShutdownDetector.class).toInstance(new ShutdownDetector());
                        bind(HealthCheckHandler.class).to(KaryonHealthCheckHandler.class).asEagerSingleton();
                        bind(HealthCheckInvocationStrategy.class).to(SyncHealthCheckInvocationStrategy.class).asEagerSingleton();
                    }
                });
                binder.include(EurekaHealthStatusModule.class);
            }
        });

        additionalModules(bootstrapModules);

        LifecycleInjectorBuilder builder = LifecycleInjector.builder();
        builder.withAdditionalBootstrapModules(bootstrapModules);
        builder.withModules(PostInjectorModule.forLifecycleInjectorBuilder(builder));
        injector = builder.build().createInjector();
        startLifecycleManager();
    }

    protected void bindConfigurationProvider(BootstrapBinder bootstrapBinder) {
        bootstrapBinder.bind(PropertiesLoader.class).toInstance(new DefaultPropertiesLoader(name));
        bootstrapBinder.bind(PropertiesInitializer.class).asEagerSingleton();

        Builder builder = ArchaiusConfigurationProvider.builder();
        builder.withOwnershipPolicy(ConfigurationOwnershipPolicies.ownsAll());
        bootstrapBinder.bindConfigurationProvider().toInstance(builder.build());
    }

    protected void bindMetricsRegistry(BootstrapBinder bootstrapBinder) {
        bootstrapBinder.include(new AbstractModule() {
            @Override
            protected void configure() {
                MetricRegistry internalRegistry = new MetricRegistry();
                final JmxReporter reporter = JmxReporter.forRegistry(internalRegistry).build();
                reporter.start();

                ExtendedRegistry registry = new ExtendedRegistry(new MetricsRegistry(Clock.SYSTEM, internalRegistry));
                bind(ExtendedRegistry.class).toInstance(registry);
            }
        });
    }

    protected abstract void additionalModules(List<BootstrapModule> suites);

    private void startLifecycleManager() throws Exception {
        lifecycleManager = injector.getInstance(LifecycleManager.class);
        lifecycleManager.start();
    }

    public void waitTillShutdown() {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                if (injectorShutdown.getCount() > 0) {
                    logger.info("Shutdown hook triggered - terminating the application");
                    shutdown();
                }
            }
        });
        while (injectorShutdown.getCount() > 0) {
            try {
                injectorShutdown.await();
            } catch (InterruptedException e) {
                logger.error("Unexpected interruption", e);
            }
        }
        long waitTimeMs = 1;
        while (lifecycleManager.getState(lifecycleManager) != LifecycleState.LATENT) {
            try {
                Thread.sleep(waitTimeMs);
                waitTimeMs = Math.min(1000, waitTimeMs * 2);
            } catch (InterruptedException e) {
                logger.error("Unexpected interruption", e);
            }
            if (waitTimeMs == 1000) {
                logger.info("Waiting for the container to complete the shutdown process");
            }
        }
        logger.info("All services stopped; quitting");
    }

    public void shutdown() {
        if (lifecycleManager != null) {
            lifecycleManager.close();
        }
    }

    private class ShutdownDetector {
        @PreDestroy
        public void shutdown() {
            injectorShutdown.countDown();
        }
    }

    private static class PropertiesInitializer {
        @Inject
        private PropertiesInitializer(PropertiesLoader loader) {
            loader.load();
        }
    }
}
