package com.github.ifrugal.lifecycle.transport.kafka;

import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.json.LifecycleJson;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One daemon poll loop shared by {@link KafkaTransport} (signals topic) and {@link KafkaNotificationConsumer}
 * (notifications topic): parse, hand to every registered handler, commit on success, retry the same record in
 * place up to {@code maxDeliveryAttempts} on a {@code RuntimeException}, then dead-letter and move on. Malformed
 * JSON is dead-lettered immediately. A {@code deliverAt} in the future is delivered anyway, with a warning — this
 * transport never honours delay ({@link KafkaTransport#supportsDelay()}).
 */
final class KafkaDeliveryLoop implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(KafkaDeliveryLoop.class);

    private final String name;
    private final KafkaConsumer<String, String> consumer;
    private final Producer<String, String> producer;
    private final String deadLetterTopic;
    private final int maxDeliveryAttempts;
    private final Duration retryBackoff;
    private final List<Consumer<LifecycleEvent>> handlers;
    private final AtomicBoolean running = new AtomicBoolean(true);

    KafkaDeliveryLoop(String name, KafkaConsumer<String, String> consumer, Producer<String, String> producer,
                       String deadLetterTopic, int maxDeliveryAttempts, Duration retryBackoff,
                       List<Consumer<LifecycleEvent>> handlers) {
        this.name = name;
        this.consumer = consumer;
        this.producer = producer;
        this.deadLetterTopic = deadLetterTopic;
        this.maxDeliveryAttempts = maxDeliveryAttempts;
        this.retryBackoff = retryBackoff;
        this.handlers = handlers;
    }

    @Override
    public void run() {
        try {
            while (running.get()) {
                ConsumerRecords<String, String> records;
                try {
                    records = consumer.poll(Duration.ofMillis(500));
                } catch (WakeupException e) {
                    if (running.get()) {
                        continue;
                    }
                    break;
                }
                for (ConsumerRecord<String, String> record : records) {
                    process(record);
                }
            }
        } catch (RuntimeException e) {
            if (running.get()) {
                log.error("{}: poll loop terminated unexpectedly", name, e);
            }
        } finally {
            try {
                consumer.close();
            } catch (RuntimeException e) {
                log.warn("{}: error closing consumer", name, e);
            }
        }
    }

    private void process(ConsumerRecord<String, String> record) {
        LifecycleEvent event;
        try {
            event = LifecycleJson.readEvent(record.value());
        } catch (RuntimeException e) {
            log.error("{}: malformed JSON at {}-{}@{}; dead-lettering: {}", name, record.topic(), record.partition(), record.offset(), e.toString());
            deadLetter(record, "malformed JSON: " + e.getMessage(), 0);
            commit(record);
            return;
        }

        if (event.deliverAt() != null && event.deliverAt().isAfter(Instant.now())) {
            log.warn("{}: {} ({}) has deliverAt {} in the future; this transport does not support delay, delivering now",
                    name, event.eventId(), event.action(), event.deliverAt());
        }

        int attempt = 0;
        while (true) {
            attempt++;
            try {
                for (Consumer<LifecycleEvent> handler : handlers) {
                    handler.accept(event);
                }
                commit(record);
                return;
            } catch (WakeupException e) {
                // shutdown in progress (close() woke the blocking commitSync/handler call, not a delivery failure):
                // propagate uncommitted so the run() loop exits cleanly and this record is redelivered after restart.
                throw e;
            } catch (RuntimeException ex) {
                if (attempt >= maxDeliveryAttempts) {
                    log.error("{}: {} ({}) failed after {} attempt(s); dead-lettering: {}", name, event.eventId(), event.action(), attempt, ex.toString());
                    deadLetter(record, describeFailure(ex), attempt);
                    commit(record);
                    return;
                }
                log.warn("{}: {} ({}) attempt {}/{} failed: {}", name, event.eventId(), event.action(), attempt, maxDeliveryAttempts, ex.toString());
                sleep(retryBackoff);
            }
        }
    }

    private void deadLetter(ConsumerRecord<String, String> record, String reason, int attempts) {
        List<Header> headers = new ArrayList<>();
        record.headers().forEach(headers::add);
        headers.add(KafkaClients.header("lifecycle-dlq-reason", reason));
        headers.add(KafkaClients.header("lifecycle-dlq-attempts", String.valueOf(attempts)));
        ProducerRecord<String, String> dlq = new ProducerRecord<>(deadLetterTopic, null, record.key(), record.value(), headers);
        try {
            producer.send(dlq).get();
        } catch (Exception e) {
            log.error("{}: failed to publish dead letter for {}-{}@{} to {}", name, record.topic(), record.partition(), record.offset(), deadLetterTopic, e);
        }
    }

    private void commit(ConsumerRecord<String, String> record) {
        consumer.commitSync(Map.of(new TopicPartition(record.topic(), record.partition()), new OffsetAndMetadata(record.offset() + 1)));
    }

    private static String describeFailure(RuntimeException ex) {
        return ex.getClass().getName() + ": " + ex.getMessage();
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void shutdown() {
        running.set(false);
        consumer.wakeup();
    }
}
