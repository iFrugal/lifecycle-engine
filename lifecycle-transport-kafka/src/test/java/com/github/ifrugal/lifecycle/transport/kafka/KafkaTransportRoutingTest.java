package com.github.ifrugal.lifecycle.transport.kafka;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.Causation;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.EventKind;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.json.LifecycleJson;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No broker needed: a {@link MockProducer} is injected in place of a real Kafka client to verify {@link
 * KafkaTransport#publish} routes and stamps a record correctly (DD-08, DD-02) without Docker.
 */
class KafkaTransportRoutingTest {

    private final KafkaTransportConfig config = KafkaTransportConfig.defaults("unused:9092");

    private MockProducer<String, String> mockProducer() {
        return new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());
    }

    private KafkaTransport transportWith(MockProducer<String, String> producer) {
        return new KafkaTransport(config, cfg -> producer);
    }

    @Test
    void signalGoesToTheSignalsTopicKeyedByEntity() {
        MockProducer<String, String> producer = mockProducer();
        try (KafkaTransport transport = transportWith(producer)) {
            LifecycleEvent event = LifecycleEvent.builder()
                    .eventId("evt-1")
                    .kind(EventKind.SIGNAL)
                    .entity(EntityRef.of("order", "o-1"))
                    .action("PAY")
                    .actor(Actor.of("u1", "customer"))
                    .payload(Map.of("amount", 100))
                    .occurredAt(Instant.parse("2026-01-01T00:00:00Z"))
                    .build();

            transport.publish(event);

            List<ProducerRecord<String, String>> sent = producer.history();
            assertThat(sent).hasSize(1);
            ProducerRecord<String, String> record = sent.get(0);
            assertThat(record.topic()).isEqualTo(KafkaTransportConfig.DEFAULT_SIGNALS_TOPIC);
            assertThat(record.key()).isEqualTo(event.entity().key());
        }
    }

    @Test
    void notificationGoesToTheNotificationsTopic() {
        MockProducer<String, String> producer = mockProducer();
        try (KafkaTransport transport = transportWith(producer)) {
            LifecycleEvent event = LifecycleEvent.builder()
                    .eventId("evt-2")
                    .kind(EventKind.NOTIFICATION)
                    .entity(EntityRef.of("order", "o-2"))
                    .action("ReceiptRequested")
                    .actor(Actor.of("u1", "customer"))
                    .occurredAt(Instant.now())
                    .build();

            transport.publish(event);

            ProducerRecord<String, String> record = producer.history().get(0);
            assertThat(record.topic()).isEqualTo(KafkaTransportConfig.DEFAULT_NOTIFICATIONS_TOPIC);
            assertThat(record.key()).isEqualTo(event.entity().key());
        }
    }

    @Test
    void keyIsTheStableEntityKeyForPerEntityOrdering() {
        MockProducer<String, String> producer = mockProducer();
        try (KafkaTransport transport = transportWith(producer)) {
            EntityRef tenantScoped = new EntityRef("acme", "shipment", "s-9");
            LifecycleEvent event = LifecycleEvent.builder()
                    .eventId("evt-3")
                    .kind(EventKind.SIGNAL)
                    .entity(tenantScoped)
                    .action("DELIVER")
                    .actor(Actor.of("c1", "courier"))
                    .occurredAt(Instant.now())
                    .build();

            transport.publish(event);

            assertThat(producer.history().get(0).key()).isEqualTo("acme/shipment/s-9");
        }
    }

    @Test
    void headersCarryEnvelopeMetadata() {
        MockProducer<String, String> producer = mockProducer();
        try (KafkaTransport transport = transportWith(producer)) {
            Instant deliverAt = Instant.parse("2030-01-01T00:00:00Z");
            LifecycleEvent parent = LifecycleEvent.builder()
                    .eventId("parent-1")
                    .kind(EventKind.SIGNAL)
                    .entity(EntityRef.of("order", "o-1"))
                    .action("PAY")
                    .actor(Actor.of("u1", "customer"))
                    .occurredAt(Instant.now())
                    .build();
            LifecycleEvent child = LifecycleEvent.builder()
                    .eventId("evt-4")
                    .kind(EventKind.SIGNAL)
                    .entity(EntityRef.of("shipment", "s-1"))
                    .action("PREPARE")
                    .actor(Actor.of("lifecycle-engine", "lifecycle-engine"))
                    .occurredAt(Instant.now())
                    .deliverAt(deliverAt)
                    .causation(new Causation(parent.causation().correlationId(), parent.eventId(), 1))
                    .build();

            transport.publish(child);

            ProducerRecord<String, String> record = producer.history().get(0);
            assertThat(header(record, "lifecycle-kind")).isEqualTo("SIGNAL");
            assertThat(header(record, "lifecycle-action")).isEqualTo("PREPARE");
            assertThat(header(record, "lifecycle-event-id")).isEqualTo("evt-4");
            assertThat(header(record, "lifecycle-correlation-id")).isEqualTo(parent.eventId());
            assertThat(header(record, "lifecycle-hop")).isEqualTo("1");
            assertThat(header(record, "lifecycle-deliver-at")).isEqualTo(deliverAt.toString());
        }
    }

    @Test
    void noDeliverAtHeaderWhenDeliverAtIsAbsent() {
        MockProducer<String, String> producer = mockProducer();
        try (KafkaTransport transport = transportWith(producer)) {
            LifecycleEvent event = LifecycleEvent.builder()
                    .eventId("evt-5")
                    .kind(EventKind.SIGNAL)
                    .entity(EntityRef.of("order", "o-1"))
                    .action("PAY")
                    .actor(Actor.of("u1", "customer"))
                    .occurredAt(Instant.now())
                    .build();

            transport.publish(event);

            ProducerRecord<String, String> record = producer.history().get(0);
            assertThat(record.headers().lastHeader("lifecycle-deliver-at")).isNull();
        }
    }

    @Test
    void bodyRoundTripsThroughLifecycleJson() {
        MockProducer<String, String> producer = mockProducer();
        try (KafkaTransport transport = transportWith(producer)) {
            LifecycleEvent event = LifecycleEvent.builder()
                    .eventId("evt-6")
                    .kind(EventKind.SIGNAL)
                    .entity(EntityRef.of("order", "o-1"))
                    .action("PAY")
                    .actor(Actor.of("u1", "customer", "payments-service"))
                    .payload(Map.of("payment", Map.of("status", "AUTHORISED", "amount", 120), "shipmentId", "s-1"))
                    .occurredAt(Instant.parse("2026-02-02T10:00:00Z"))
                    .build();

            transport.publish(event);

            String json = producer.history().get(0).value();
            LifecycleEvent roundTripped = LifecycleJson.readEvent(json);
            assertThat(roundTripped).isEqualTo(event);
        }
    }

    @Test
    void supportsDelayIsFalse() {
        try (KafkaTransport transport = transportWith(mockProducer())) {
            assertThat(transport.supportsDelay()).isFalse();
        }
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        Header h = record.headers().lastHeader(name);
        assertThat(h).as("header " + name).isNotNull();
        return new String(h.value(), StandardCharsets.UTF_8);
    }
}
