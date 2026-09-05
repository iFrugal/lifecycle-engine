package com.github.ifrugal.lifecycle.transport.kafka;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Everything {@link KafkaTransport} needs to talk to a broker. Immutable; {@code withX} methods return a
 * modified copy so a caller can start from {@link #defaults(String)} and override only what differs.
 *
 * @param bootstrapServers    Kafka {@code bootstrap.servers}
 * @param signalsTopic        topic signals are published to and consumed from (DD-08: keyed per entity)
 * @param notificationsTopic  topic notifications are published to
 * @param deadLetterTopic     topic a record is republished to once redelivery is exhausted
 * @param consumerGroup       consumer group id for the signals subscription
 * @param maxDeliveryAttempts total delivery attempts (including the first) before dead-lettering
 * @param retryBackoff        delay between redelivery attempts of the same record
 * @param extraProducerProps  escape hatch merged over the producer defaults
 * @param extraConsumerProps  escape hatch merged over the consumer defaults
 */
public record KafkaTransportConfig(
        String bootstrapServers,
        String signalsTopic,
        String notificationsTopic,
        String deadLetterTopic,
        String consumerGroup,
        int maxDeliveryAttempts,
        Duration retryBackoff,
        Map<String, Object> extraProducerProps,
        Map<String, Object> extraConsumerProps) {

    public static final String DEFAULT_SIGNALS_TOPIC = "lifecycle.signals";
    public static final String DEFAULT_NOTIFICATIONS_TOPIC = "lifecycle.notifications";
    public static final String DEFAULT_CONSUMER_GROUP = "lifecycle-engine";
    public static final int DEFAULT_MAX_DELIVERY_ATTEMPTS = 5;
    public static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofMillis(200);

    public KafkaTransportConfig {
        Objects.requireNonNull(bootstrapServers, "bootstrapServers");
        Objects.requireNonNull(signalsTopic, "signalsTopic");
        Objects.requireNonNull(notificationsTopic, "notificationsTopic");
        Objects.requireNonNull(deadLetterTopic, "deadLetterTopic");
        Objects.requireNonNull(consumerGroup, "consumerGroup");
        Objects.requireNonNull(retryBackoff, "retryBackoff");
        if (maxDeliveryAttempts < 1) {
            throw new IllegalArgumentException("maxDeliveryAttempts must be >= 1");
        }
        extraProducerProps = extraProducerProps == null ? Map.of() : Map.copyOf(extraProducerProps);
        extraConsumerProps = extraConsumerProps == null ? Map.of() : Map.copyOf(extraConsumerProps);
    }

    public static KafkaTransportConfig defaults(String bootstrapServers) {
        return new KafkaTransportConfig(
                bootstrapServers,
                DEFAULT_SIGNALS_TOPIC,
                DEFAULT_NOTIFICATIONS_TOPIC,
                DEFAULT_SIGNALS_TOPIC + ".dlq",
                DEFAULT_CONSUMER_GROUP,
                DEFAULT_MAX_DELIVERY_ATTEMPTS,
                DEFAULT_RETRY_BACKOFF,
                Map.of(),
                Map.of());
    }

    public KafkaTransportConfig withBootstrapServers(String v) {
        return new KafkaTransportConfig(v, signalsTopic, notificationsTopic, deadLetterTopic, consumerGroup, maxDeliveryAttempts, retryBackoff, extraProducerProps, extraConsumerProps);
    }

    public KafkaTransportConfig withSignalsTopic(String v) {
        return new KafkaTransportConfig(bootstrapServers, v, notificationsTopic, deadLetterTopic, consumerGroup, maxDeliveryAttempts, retryBackoff, extraProducerProps, extraConsumerProps);
    }

    public KafkaTransportConfig withNotificationsTopic(String v) {
        return new KafkaTransportConfig(bootstrapServers, signalsTopic, v, deadLetterTopic, consumerGroup, maxDeliveryAttempts, retryBackoff, extraProducerProps, extraConsumerProps);
    }

    public KafkaTransportConfig withDeadLetterTopic(String v) {
        return new KafkaTransportConfig(bootstrapServers, signalsTopic, notificationsTopic, v, consumerGroup, maxDeliveryAttempts, retryBackoff, extraProducerProps, extraConsumerProps);
    }

    public KafkaTransportConfig withConsumerGroup(String v) {
        return new KafkaTransportConfig(bootstrapServers, signalsTopic, notificationsTopic, deadLetterTopic, v, maxDeliveryAttempts, retryBackoff, extraProducerProps, extraConsumerProps);
    }

    public KafkaTransportConfig withMaxDeliveryAttempts(int v) {
        return new KafkaTransportConfig(bootstrapServers, signalsTopic, notificationsTopic, deadLetterTopic, consumerGroup, v, retryBackoff, extraProducerProps, extraConsumerProps);
    }

    public KafkaTransportConfig withRetryBackoff(Duration v) {
        return new KafkaTransportConfig(bootstrapServers, signalsTopic, notificationsTopic, deadLetterTopic, consumerGroup, maxDeliveryAttempts, v, extraProducerProps, extraConsumerProps);
    }

    public KafkaTransportConfig withExtraProducerProps(Map<String, Object> v) {
        return new KafkaTransportConfig(bootstrapServers, signalsTopic, notificationsTopic, deadLetterTopic, consumerGroup, maxDeliveryAttempts, retryBackoff, v, extraConsumerProps);
    }

    public KafkaTransportConfig withExtraConsumerProps(Map<String, Object> v) {
        return new KafkaTransportConfig(bootstrapServers, signalsTopic, notificationsTopic, deadLetterTopic, consumerGroup, maxDeliveryAttempts, retryBackoff, extraProducerProps, v);
    }
}
