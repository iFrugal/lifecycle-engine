package com.github.ifrugal.lifecycle.starter;

import com.github.ifrugal.lifecycle.api.LifecycleEngine;
import com.github.ifrugal.lifecycle.api.spi.StateStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The operator's view (DD-13, bean 10): the {@code lifecyclerules} actuator endpoint, the {@code lifecycleRules}
 * health indicator, and the Micrometer meters. Each is conditional on its own library being present, so an
 * application without actuator or without micrometer gets the rest of the starter unchanged.
 *
 * <p>Spring Boot 4 split health out of {@code spring-boot-actuator} into {@code spring-boot-health}
 * ({@code org.springframework.boot.health.contributor}), so the endpoint and the health indicator are gated on
 * two different types rather than one.
 */
@AutoConfiguration(after = {LifecycleCoreAutoConfiguration.class, LifecycleTasksAutoConfiguration.class})
@ConditionalOnBooleanProperty(name = "lifecycle.enabled", matchIfMissing = true)
@EnableConfigurationProperties(LifecycleProperties.class)
public class LifecycleManagementAutoConfiguration {

    /** {@code lifecycle.management.reload-endpoint} */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Endpoint.class)
    @ConditionalOnBooleanProperty(name = "lifecycle.management.reload-endpoint", matchIfMissing = true)
    public static class EndpointConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public LifecycleRulesEndpoint lifecycleRulesEndpoint(LifecycleRuleReloader reloader) {
            return new LifecycleRulesEndpoint(reloader);
        }
    }

    /** {@code lifecycle.management.health}. The bean name fixes the contributor id to {@code lifecycleRules}. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnBooleanProperty(name = "lifecycle.management.health", matchIfMissing = true)
    public static class HealthConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "lifecycleRulesHealthIndicator")
        public LifecycleRulesHealthIndicator lifecycleRulesHealthIndicator(LifecycleRuleReloader reloader) {
            return new LifecycleRulesHealthIndicator(reloader);
        }
    }

    /** {@code lifecycle.management.metrics} */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({MeterRegistry.class, MeterBinder.class})
    @ConditionalOnBooleanProperty(name = "lifecycle.management.metrics", matchIfMissing = true)
    public static class MetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public LifecycleMeterBinder lifecycleMeterBinder(LifecycleRuleReloader reloader, StateStore store) {
            return new LifecycleMeterBinder(reloader, store);
        }

        /**
         * Wraps whichever {@code LifecycleEngine} bean exists in {@link MeteredLifecycleEngine}, so outcomes are
         * counted for every caller including the {@code Dispatcher} — a decorator rather than a second engine
         * bean, because two beans of the same type would make every injection point ambiguous.
         *
         * <p>{@code static}, so the enclosing configuration is not instantiated early just to create a
         * {@code BeanPostProcessor}, and the registry is looked up lazily for the same reason.
         */
        @Bean
        public static BeanPostProcessor lifecycleEngineMetricsPostProcessor(ObjectProvider<MeterRegistry> meters) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof LifecycleEngine engine) || bean instanceof MeteredLifecycleEngine) {
                        return bean;
                    }
                    MeterRegistry registry = meters.getIfAvailable();
                    return registry == null ? bean : new MeteredLifecycleEngine(engine, registry);
                }
            };
        }
    }
}
