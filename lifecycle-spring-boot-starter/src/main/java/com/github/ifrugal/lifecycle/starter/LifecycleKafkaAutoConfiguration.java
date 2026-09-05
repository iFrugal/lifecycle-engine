package com.github.ifrugal.lifecycle.starter;

import com.github.ifrugal.lifecycle.api.spi.Transport;
import com.github.ifrugal.lifecycle.tasks.TaskService;
import com.github.ifrugal.lifecycle.transport.kafka.KafkaNotificationConsumer;
import com.github.ifrugal.lifecycle.transport.kafka.KafkaTransport;
import com.github.ifrugal.lifecycle.transport.kafka.KafkaTransportConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * The Kafka transport (DD-13, {@code lifecycle.transport=kafka}). Runs {@code before}
 * {@link LifecycleCoreAutoConfiguration} so this transport is registered before the in-memory default.
 *
 * <p>{@code KafkaTransport} does not support delayed delivery, so a rule set declaring an {@code after} timer is
 * refused at load time by {@link LifecycleRuleReloader} rather than silently firing timers immediately (DD-09).
 */
@AutoConfiguration(before = LifecycleCoreAutoConfiguration.class)
@ConditionalOnBooleanProperty(name = "lifecycle.enabled", matchIfMissing = true)
@ConditionalOnClass(KafkaTransport.class)
@ConditionalOnProperty(prefix = "lifecycle", name = "transport", havingValue = "kafka")
@EnableConfigurationProperties(LifecycleProperties.class)
public class LifecycleKafkaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public KafkaTransportConfig lifecycleKafkaTransportConfig(LifecycleProperties properties) {
        LifecycleProperties.Kafka kafka = properties.getKafka();
        if (!StringUtils.hasText(kafka.getBootstrapServers())) {
            throw new IllegalStateException("lifecycle.transport=kafka requires lifecycle.kafka.bootstrap-servers");
        }
        return new KafkaTransportConfig(
                kafka.getBootstrapServers(),
                kafka.getSignalsTopic(),
                kafka.getNotificationsTopic(),
                kafka.getDeadLetterTopic(),
                kafka.getConsumerGroup(),
                kafka.getMaxDeliveryAttempts(),
                kafka.getRetryBackoff(),
                Map.of(),
                Map.of());
    }

    /** Bean 5. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(Transport.class)
    public KafkaTransport lifecycleTransport(KafkaTransportConfig config) {
        return new KafkaTransport(config);
    }

    /**
     * Bean 9's transport half for Kafka: an independent consumer of the notifications topic that feeds
     * {@code lifecycle.task.create} into the {@code TaskService} (the service itself ignores anything else).
     * Its own consumer group, so it does not compete with the application's own notification consumers.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnClass(TaskService.class)
    @ConditionalOnMissingBean
    @ConditionalOnBooleanProperty(name = "lifecycle.tasks.enabled")
    public KafkaNotificationConsumer lifecycleKafkaNotificationConsumer(KafkaTransportConfig config, TaskService tasks) {
        return new KafkaNotificationConsumer(config, config.consumerGroup() + "-tasks", tasks.asNotificationListener());
    }
}
