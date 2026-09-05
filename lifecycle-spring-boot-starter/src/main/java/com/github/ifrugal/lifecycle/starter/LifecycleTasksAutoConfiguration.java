package com.github.ifrugal.lifecycle.starter;

import com.github.ifrugal.lifecycle.api.spi.Transport;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryTransport;
import com.github.ifrugal.lifecycle.tasks.InMemoryTaskStore;
import com.github.ifrugal.lifecycle.tasks.TaskService;
import com.github.ifrugal.lifecycle.tasks.TaskStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * Human tasks (DD-10, DD-13 bean 9), switched on by {@code lifecycle.tasks.enabled=true}.
 *
 * <p>The {@code TaskStore} matching {@code lifecycle.store} comes from that store's own auto-configuration; this
 * class supplies the in-memory fallback and the {@code TaskService} itself, and for the in-memory transport it
 * subscribes the service to the notification feed. For Kafka the equivalent subscription is a
 * {@code KafkaNotificationConsumer} in {@link LifecycleKafkaAutoConfiguration}, because only that class knows
 * the broker configuration.
 */
@AutoConfiguration(after = {
        LifecycleCoreAutoConfiguration.class,
        LifecycleJdbcAutoConfiguration.class,
        LifecycleMongoAutoConfiguration.class,
        LifecycleKafkaAutoConfiguration.class})
@ConditionalOnBooleanProperty(name = "lifecycle.enabled", matchIfMissing = true)
@ConditionalOnClass(TaskService.class)
@ConditionalOnBooleanProperty(name = "lifecycle.tasks.enabled")
@EnableConfigurationProperties(LifecycleProperties.class)
public class LifecycleTasksAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TaskStore.class)
    public InMemoryTaskStore lifecycleTaskStore() {
        return new InMemoryTaskStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskService lifecycleTaskService(TaskStore store, Transport transport) {
        return new TaskService(store, transport, Clock.systemUTC());
    }

    /**
     * {@code transport: memory}. The reference transport hands notifications to registered listeners rather
     * than to a broker, so the service is wired straight onto it (DD-13, bean 9).
     */
    @Bean
    @ConditionalOnMissingBean
    public InMemoryTaskNotificationBridge lifecycleTaskNotificationBridge(TaskService tasks, Transport transport) {
        return new InMemoryTaskNotificationBridge(tasks, transport);
    }

    /** Registers {@code TaskService.asNotificationListener()} on an {@link InMemoryTransport}; a no-op otherwise. */
    public static final class InMemoryTaskNotificationBridge {

        private final boolean attached;

        InMemoryTaskNotificationBridge(TaskService tasks, Transport transport) {
            if (transport instanceof InMemoryTransport inMemory) {
                inMemory.onNotification(tasks.asNotificationListener());
                this.attached = true;
            } else {
                this.attached = false;
            }
        }

        public boolean isAttached() {
            return attached;
        }
    }
}
