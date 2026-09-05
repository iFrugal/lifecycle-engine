package com.github.ifrugal.lifecycle.transport.kafka;

import com.github.ifrugal.lifecycle.api.model.EventKind;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.spi.Transport;
import com.github.ifrugal.lifecycle.json.LifecycleJson;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Kafka implementation of the transport seam (DD-08, DD-09, DD-11).
 *
 * <p><b>Ordering.</b> Every record is keyed by {@link com.github.ifrugal.lifecycle.api.model.EntityRef#key()},
 * so records for one entity land on one partition and are read back in the order they were produced (DD-08). No
 * ordering is claimed or needed across entities.
 *
 * <p><b>Publish.</b> Signals go to {@code signalsTopic}, notifications to {@code notificationsTopic} (routed by
 * {@link LifecycleEvent#kind()}). The send is synchronous — {@link #publish} blocks on the produce future and
 * rethrows a failure as {@link KafkaTransportException} — so a publish failure surfaces to the engine's
 * post-commit publish step exactly as DD-07 expects; retrying a failed publish is the outbox relay's job, not
 * this transport's.
 *
 * <p><b>Subscribe.</b> One daemon consumer thread reads {@code signalsTopic} with {@code enable.auto.commit=false}
 * and {@code isolation.level=read_committed}. Every registered handler is invoked for each record; a handler that
 * throws (including {@code RedeliveryRequested}) causes the same record to be retried in place, up to
 * {@code maxDeliveryAttempts} times with {@code retryBackoff} between attempts, before it is copied to
 * {@code deadLetterTopic} (with {@code lifecycle-dlq-reason} and {@code lifecycle-dlq-attempts} headers) and the
 * offset is committed regardless, so the loop moves on. Malformed JSON is dead-lettered immediately, without
 * retry.
 *
 * <p><b>No delay.</b> {@link #supportsDelay()} is {@code false}: a record whose {@code deliverAt} is in the
 * future is delivered immediately anyway, with a warning logged. {@code DefaultLifecycleEngine} refuses to start
 * against this transport if any loaded rule declares an {@code after} timer (DD-09) — there is no queue here that
 * can hold a message until its time.
 */
public final class KafkaTransport implements Transport, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaTransport.class);

    private final KafkaTransportConfig config;
    private final Producer<String, String> producer;
    private final List<Consumer<LifecycleEvent>> handlers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean consumerStarted = new AtomicBoolean(false);

    private volatile KafkaConsumer<String, String> consumer;
    private volatile KafkaDeliveryLoop loop;
    private volatile Thread consumerThread;

    public KafkaTransport(KafkaTransportConfig config) {
        this(config, KafkaClients::newProducer);
    }

    /** For tests: inject a {@link Producer} (e.g. a {@code MockProducer}) instead of connecting to a real broker. */
    KafkaTransport(KafkaTransportConfig config, Function<KafkaTransportConfig, Producer<String, String>> producerFactory) {
        this.config = Objects.requireNonNull(config, "config");
        this.producer = producerFactory.apply(config);
    }

    @Override
    public void publish(LifecycleEvent event) {
        String topic = event.kind() == EventKind.SIGNAL ? config.signalsTopic() : config.notificationsTopic();
        String key = event.entity().key();
        String value = LifecycleJson.write(event);
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, null, key, value, KafkaClients.headersFor(event));
        try {
            producer.send(record).get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new KafkaTransportException("failed to publish " + event.eventId() + " (" + event.action() + ") to " + topic, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaTransportException("interrupted publishing " + event.eventId() + " to " + topic, e);
        }
    }

    @Override
    public void subscribe(Consumer<LifecycleEvent> inbound) {
        handlers.add(Objects.requireNonNull(inbound, "inbound"));
        startConsumerIfNeeded();
    }

    private void startConsumerIfNeeded() {
        if (!consumerStarted.compareAndSet(false, true)) {
            return;
        }
        KafkaConsumer<String, String> c = KafkaClients.newConsumer(config, config.consumerGroup());
        c.subscribe(List.of(config.signalsTopic()));
        this.consumer = c;
        this.loop = new KafkaDeliveryLoop("kafka-transport[" + config.signalsTopic() + "]", c, producer,
                config.deadLetterTopic(), config.maxDeliveryAttempts(), config.retryBackoff(), handlers);
        Thread t = new Thread(loop, "lifecycle-kafka-transport-" + config.signalsTopic());
        t.setDaemon(true);
        t.start();
        this.consumerThread = t;
    }

    /**
     * Always {@code false}. {@code deliverAt} on a record is honoured by delivering immediately (with a warning),
     * never by delaying — there is no timer queue in this transport.
     */
    @Override
    public boolean supportsDelay() {
        return false;
    }

    @Override
    public void close() {
        KafkaDeliveryLoop l = this.loop;
        Thread t = this.consumerThread;
        if (l != null) {
            l.shutdown();
        }
        if (t != null) {
            try {
                t.join(TimeUnit.SECONDS.toMillis(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // KafkaDeliveryLoop closes the consumer itself once its poll loop exits.
        try {
            producer.close();
        } catch (RuntimeException e) {
            log.warn("error closing producer", e);
        }
    }
}
