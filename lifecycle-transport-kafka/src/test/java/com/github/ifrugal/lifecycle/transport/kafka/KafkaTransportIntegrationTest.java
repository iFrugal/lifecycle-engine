package com.github.ifrugal.lifecycle.transport.kafka;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.Causation;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.EventKind;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.Outcome;
import com.github.ifrugal.lifecycle.api.model.StateRecord;
import com.github.ifrugal.lifecycle.api.rules.EmitDocument;
import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;
import com.github.ifrugal.lifecycle.api.rules.TargetDocument;
import com.github.ifrugal.lifecycle.api.rules.TransitionDocument;
import com.github.ifrugal.lifecycle.core.engine.DefaultLifecycleEngine;
import com.github.ifrugal.lifecycle.core.engine.Dispatcher;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryStateStore;
import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import com.github.ifrugal.lifecycle.core.registry.InMemoryDefinitionSource;
import com.github.ifrugal.lifecycle.core.rules.GuardRegistry;
import com.github.ifrugal.lifecycle.transport.kafka.testdomain.OrderShipmentRules;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Requires Docker; skipped otherwise. One shared broker for the whole class, one set of uniquely-named topics
 * and consumer groups per test method so tests do not interfere with each other.
 */
@Testcontainers(disabledWithoutDocker = true)
class KafkaTransportIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    private static Admin admin;
    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeAll
    static void createAdminClient() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        admin = Admin.create(props);
    }

    @AfterAll
    static void closeAdminClient() {
        admin.close();
    }

    private KafkaTransportConfig uniqueConfig(String label) {
        String suffix = label + "-" + SEQ.incrementAndGet();
        KafkaTransportConfig config = KafkaTransportConfig.defaults(KAFKA.getBootstrapServers())
                .withSignalsTopic("lifecycle.signals." + suffix)
                .withNotificationsTopic("lifecycle.notifications." + suffix)
                .withDeadLetterTopic("lifecycle.signals." + suffix + ".dlq")
                .withConsumerGroup("lifecycle-engine-" + suffix)
                .withRetryBackoff(Duration.ofMillis(100));
        createTopics(config.signalsTopic(), config.notificationsTopic(), config.deadLetterTopic());
        return config;
    }

    private static void createTopics(String... topics) {
        try {
            List<NewTopic> newTopics = Arrays.stream(topics).map(t -> new NewTopic(t, 1, (short) 1)).toList();
            admin.createTopics(newTopics).all().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("failed to create topics " + Arrays.toString(topics), e);
        }
    }

    // ---- (a) publish -> subscribe round trip of a SIGNAL preserving every field ----

    @Test
    void publishSubscribeRoundTripPreservesEveryField() throws Exception {
        KafkaTransportConfig config = uniqueConfig("roundtrip");
        java.util.concurrent.BlockingQueue<LifecycleEvent> received = new java.util.concurrent.LinkedBlockingQueue<>();

        try (KafkaTransport transport = new KafkaTransport(config)) {
            transport.subscribe(received::add);

            LifecycleEvent event = LifecycleEvent.builder()
                    .eventId("evt-roundtrip-1")
                    .kind(EventKind.SIGNAL)
                    .entity(new EntityRef("acme", "order", "o-1"))
                    .action("PAY")
                    .actor(Actor.of("u1", "customer"))
                    .payload(Map.of("payment", Map.of("status", "AUTHORISED", "amount", 120), "shipmentId", "s-1"))
                    .occurredAt(Instant.now().truncatedTo(ChronoUnit.MILLIS))
                    .causation(new Causation("corr-1", "cause-1", 2))
                    .expectedVersion(3L)
                    .build();

            transport.publish(event);

            LifecycleEvent got = received.poll(30, TimeUnit.SECONDS);
            assertThat(got).isNotNull();
            assertThat(got).isEqualTo(event);
        }
    }

    // ---- (b) ordering: 50 signals for one entity arrive in order ----

    @Test
    void fiftySignalsForOneEntityArriveInOrder() throws Exception {
        KafkaTransportConfig config = uniqueConfig("ordering");
        List<Integer> receivedOrder = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(50);

        try (KafkaTransport transport = new KafkaTransport(config)) {
            transport.subscribe(e -> {
                receivedOrder.add((Integer) e.payload().get("seq"));
                latch.countDown();
            });

            EntityRef entity = EntityRef.of("order", "o-ordering");
            for (int i = 0; i < 50; i++) {
                transport.publish(LifecycleEvent.builder()
                        .entity(entity).action("PING").actor(Actor.of("u1", "customer"))
                        .payload(Map.of("seq", i))
                        .occurredAt(Instant.now())
                        .build());
            }

            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(receivedOrder).containsExactlyElementsOf(IntStream.range(0, 50).boxed().toList());
        }
    }

    // ---- (c) redelivery ----

    @Test
    void throwsTwiceThenSucceedsIsDeliveredThreeTimesWithNoDeadLetter() throws Exception {
        KafkaTransportConfig config = uniqueConfig("retry-success");
        AtomicInteger invocations = new AtomicInteger();
        CountDownLatch succeeded = new CountDownLatch(1);

        try (KafkaTransport transport = new KafkaTransport(config)) {
            transport.subscribe(e -> {
                int n = invocations.incrementAndGet();
                if (n < 3) {
                    throw new RuntimeException("synthetic failure #" + n);
                }
                succeeded.countDown();
            });

            transport.publish(LifecycleEvent.builder()
                    .entity(EntityRef.of("order", "o-retry-ok")).action("PAY").actor(Actor.of("u1", "customer"))
                    .occurredAt(Instant.now())
                    .build());

            assertThat(succeeded.await(30, TimeUnit.SECONDS)).isTrue();
            // give the loop a moment: it must not keep retrying or dead-letter after success
            Thread.sleep(1000);
            assertThat(invocations.get()).isEqualTo(3);
            assertThat(pollRecords(config.deadLetterTopic(), 1, Duration.ofSeconds(3))).isEmpty();
        }
    }

    @Test
    void alwaysThrowsGoesToDeadLetterAndConsumerMovesOn() throws Exception {
        int maxAttempts = 3;
        KafkaTransportConfig config = uniqueConfig("retry-dlq").withMaxDeliveryAttempts(maxAttempts).withRetryBackoff(Duration.ofMillis(50));
        AtomicInteger invocations = new AtomicInteger();
        CountDownLatch movedOn = new CountDownLatch(1);

        try (KafkaTransport transport = new KafkaTransport(config)) {
            transport.subscribe(e -> {
                if (e.eventId().equals("evt-always-fails")) {
                    invocations.incrementAndGet();
                    throw new RuntimeException("boom");
                }
                movedOn.countDown();
            });

            transport.publish(LifecycleEvent.builder()
                    .eventId("evt-always-fails")
                    .entity(EntityRef.of("order", "o-retry-dlq")).action("PAY").actor(Actor.of("u1", "customer"))
                    .occurredAt(Instant.now())
                    .build());

            List<ConsumerRecord<String, String>> dlqRecords = pollRecords(config.deadLetterTopic(), 1, Duration.ofSeconds(30));
            assertThat(dlqRecords).hasSize(1);
            ConsumerRecord<String, String> dlq = dlqRecords.get(0);
            assertThat(headerValue(dlq, "lifecycle-dlq-attempts")).isEqualTo(String.valueOf(maxAttempts));
            assertThat(headerValue(dlq, "lifecycle-dlq-reason")).isNotBlank();
            assertThat(invocations.get()).isEqualTo(maxAttempts);

            // the consumer committed past the poisoned record and kept going
            transport.publish(LifecycleEvent.builder()
                    .entity(EntityRef.of("order", "o-retry-dlq")).action("PING").actor(Actor.of("u1", "customer"))
                    .occurredAt(Instant.now())
                    .build());
            assertThat(movedOn.await(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void malformedJsonIsDeadLetteredImmediately() throws Exception {
        KafkaTransportConfig config = uniqueConfig("malformed");
        AtomicInteger invocations = new AtomicInteger();

        try (KafkaTransport transport = new KafkaTransport(config)) {
            transport.subscribe(e -> invocations.incrementAndGet());

            Properties props = new Properties();
            props.put(org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
            props.put(org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringSerializer.class.getName());
            props.put(org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringSerializer.class.getName());
            try (var rawProducer = new org.apache.kafka.clients.producer.KafkaProducer<String, String>(props)) {
                rawProducer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(config.signalsTopic(), "order/o-x", "{ not json")).get();
            }

            List<ConsumerRecord<String, String>> dlqRecords = pollRecords(config.deadLetterTopic(), 1, Duration.ofSeconds(30));
            assertThat(dlqRecords).hasSize(1);
            assertThat(headerValue(dlqRecords.get(0), "lifecycle-dlq-reason")).contains("malformed JSON");
            Thread.sleep(500);
            assertThat(invocations.get()).isZero();
        }
    }

    // ---- (d) cross-entity flow over Kafka ----

    @Test
    void orderAndShipmentCascadeAcrossKafkaToCompletion() throws Exception {
        KafkaTransportConfig config = uniqueConfig("cross-entity");

        var store = new InMemoryStateStore();
        var guards = GuardRegistry.empty();
        var registry = new DefinitionRegistry(new InMemoryDefinitionSource(OrderShipmentRules.all()), guards, store);
        registry.reloadOrThrow();

        List<LifecycleEvent> notifications = Collections.synchronizedList(new ArrayList<>());

        try (KafkaTransport transport = new KafkaTransport(config);
             KafkaNotificationConsumer notificationConsumer = new KafkaNotificationConsumer(config, config.consumerGroup() + "-notif", notifications::add)) {

            var engine = new DefaultLifecycleEngine(registry, store, transport, guards);
            new Dispatcher(engine, 3).attachTo(transport);

            EntityRef order = EntityRef.of("order", "o-1");
            EntityRef shipment = EntityRef.of("shipment", "s-1");

            LifecycleEvent pay = LifecycleEvent.builder()
                    .entity(order).action("PAY").actor(Actor.of("u1", "customer"))
                    .payload(Map.of("payment", Map.of("status", "AUTHORISED", "amount", 120), "shipmentId", "s-1"))
                    .build();

            Outcome payOutcome = engine.handle(pay);
            assertThat(payOutcome).isInstanceOf(Outcome.Applied.class);
            assertThat(((Outcome.Applied) payOutcome).to()).isEqualTo("PAID");

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(store.find(shipment).map(StateRecord::state)).contains("PREPARED");
                assertThat(store.find(order).map(StateRecord::state)).contains("FULFILLING");
            });

            LifecycleEvent deliver = LifecycleEvent.builder()
                    .entity(shipment).action("DELIVER").actor(Actor.of("c1", "courier"))
                    .payload(Map.of("orderId", "o-1"))
                    .build();

            Outcome deliverOutcome = engine.handle(deliver);
            assertThat(deliverOutcome).isInstanceOf(Outcome.Applied.class);
            assertThat(((Outcome.Applied) deliverOutcome).to()).isEqualTo("DELIVERED");

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(store.find(shipment).map(StateRecord::state)).contains("DELIVERED");
                assertThat(store.find(order).map(StateRecord::state)).contains("COMPLETED");
            });

            await().atMost(Duration.ofSeconds(30)).until(() ->
                    notifications.stream().anyMatch(n -> n.action().equals("ReceiptRequested")));
        }
    }

    // ---- (e) rules with `after` refused at startup ----

    @Test
    void engineRefusesStartupWhenLoadedRulesDeclareAfterTimers() {
        KafkaTransportConfig config = uniqueConfig("after-refused");

        RuleSetDocument withTimer = new RuleSetDocument(null, "widget", "NEW", 16,
                List.of("NEW", "WAITING"),
                List.of(TransitionDocument.builder("widget.start")
                        .from("NEW").on("START").roles("system")
                        .to("WAITING")
                        .emit(new EmitDocument("PING", TargetDocument.toSelf(), null, Duration.ofMinutes(5), null, null))
                        .build()));

        var store = new InMemoryStateStore();
        var guards = GuardRegistry.empty();
        var registry = new DefinitionRegistry(new InMemoryDefinitionSource(List.of(withTimer)), guards, store);
        registry.reloadOrThrow();
        assertThat(registry.requiresDelay()).isTrue();

        try (KafkaTransport transport = new KafkaTransport(config)) {
            assertThat(transport.supportsDelay()).isFalse();
            assertThatThrownBy(() -> new DefaultLifecycleEngine(registry, store, transport, guards))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("after");
        }
    }

    // ---- helpers ----

    private List<ConsumerRecord<String, String>> pollRecords(String topic, int atLeast, Duration timeout) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "poll-" + topic + "-" + SEQ.incrementAndGet());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (found.size() < atLeast && System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(300));
                records.forEach(found::add);
            }
        }
        return found;
    }

    private static String headerValue(ConsumerRecord<String, String> record, String name) {
        Header h = record.headers().lastHeader(name);
        assertThat(h).as("header " + name).isNotNull();
        return new String(h.value(), StandardCharsets.UTF_8);
    }
}
