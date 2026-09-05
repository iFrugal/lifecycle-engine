package com.github.ifrugal.lifecycle.transport.kafka;

import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A standalone reader of {@code notificationsTopic} for effect consumers outside the engine (DD-09: the engine
 * never consumes notifications, so this is not wired into {@link KafkaTransport}). Same retry-then-dead-letter
 * policy as {@link KafkaTransport#subscribe}, on its own daemon consumer thread and its own {@code groupId} so
 * multiple independent listeners can each see every notification.
 */
public final class KafkaNotificationConsumer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaNotificationConsumer.class);

    private final KafkaConsumer<String, String> consumer;
    private final Producer<String, String> producer;
    private final KafkaDeliveryLoop loop;
    private final Thread thread;

    public KafkaNotificationConsumer(KafkaTransportConfig config, String groupId, Consumer<LifecycleEvent> handler) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(handler, "handler");

        this.producer = KafkaClients.newProducer(config);
        this.consumer = KafkaClients.newConsumer(config, groupId);
        this.consumer.subscribe(List.of(config.notificationsTopic()));
        this.loop = new KafkaDeliveryLoop("kafka-notifications[" + config.notificationsTopic() + "/" + groupId + "]",
                consumer, producer, config.deadLetterTopic(), config.maxDeliveryAttempts(), config.retryBackoff(), List.of(handler));
        this.thread = new Thread(loop, "lifecycle-kafka-notifications-" + groupId);
        this.thread.setDaemon(true);
        this.thread.start();
    }

    @Override
    public void close() {
        loop.shutdown();
        try {
            thread.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // KafkaDeliveryLoop closes the consumer itself once its poll loop exits.
        try {
            producer.close();
        } catch (RuntimeException e) {
            log.warn("error closing producer", e);
        }
    }
}
