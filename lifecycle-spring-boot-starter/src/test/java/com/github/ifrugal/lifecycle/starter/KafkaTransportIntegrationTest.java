package com.github.ifrugal.lifecycle.starter;

import com.github.ifrugal.lifecycle.api.LifecycleEngine;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.Outcome;
import com.github.ifrugal.lifecycle.api.spi.StateStore;
import com.github.ifrugal.lifecycle.api.spi.Transport;
import com.github.ifrugal.lifecycle.starter.testapp.Events;
import com.github.ifrugal.lifecycle.starter.testapp.TestApplication;
import com.github.ifrugal.lifecycle.transport.kafka.KafkaTransport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@code lifecycle.transport=kafka} against a real broker (DD-13). The interesting property is that nothing in
 * the application changes: the same rules, the same engine call, and the cascade to another entity now leaves
 * the process, comes back through the consumer thread, and lands on the {@code Dispatcher} the starter attached.
 *
 * <p>These rules are the timer-free copies under {@code rules-notimer}: {@code KafkaTransport} has no timer
 * queue, so a rule set declaring {@code after} is refused at load time rather than firing timers immediately.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = TestApplication.class,
        properties = {
                "lifecycle.rules.files=classpath:rules-notimer/order.yaml,classpath:rules-notimer/shipment.yaml",
                "lifecycle.rules.reload.poll=0",
                "lifecycle.transport=kafka"
        })
class KafkaTransportIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        String run = UUID.randomUUID().toString().substring(0, 8);
        registry.add("lifecycle.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("lifecycle.kafka.signals-topic", () -> "lifecycle.signals." + run);
        registry.add("lifecycle.kafka.notifications-topic", () -> "lifecycle.notifications." + run);
        registry.add("lifecycle.kafka.dead-letter-topic", () -> "lifecycle.signals." + run + ".dlq");
        registry.add("lifecycle.kafka.consumer-group", () -> "lifecycle-engine-" + run);
    }

    @Autowired
    LifecycleEngine engine;
    @Autowired
    StateStore store;
    @Autowired
    Transport transport;

    @Test
    void the_transport_bean_is_the_kafka_one() {
        assertThat(transport).isInstanceOf(KafkaTransport.class);
        assertThat(transport.supportsDelay()).isFalse();
    }

    @Test
    void a_cross_entity_cascade_completes_over_kafka() {
        Outcome paid = engine.handle(Events.pay("o-kafka", "s-kafka"));
        assertThat(paid).isInstanceOf(Outcome.Applied.class);

        // order.pay -> PREPARE (Kafka) -> shipment.prepare -> SHIPMENT_PREPARED (Kafka) -> order.on-shipment-prepared
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            assertThat(store.find(EntityRef.of("shipment", "s-kafka"))).hasValueSatisfying(
                    record -> assertThat(record.state()).isEqualTo("PREPARED"));
            assertThat(store.find(EntityRef.of("order", "o-kafka"))).hasValueSatisfying(
                    record -> assertThat(record.state()).isEqualTo("FULFILLING"));
        });
    }
}
