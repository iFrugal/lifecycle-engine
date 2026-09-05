package com.github.ifrugal.lifecycle.transport.kafka;

import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Shared producer/consumer construction and header mapping for {@link KafkaTransport} and {@link KafkaNotificationConsumer}. */
final class KafkaClients {

    private KafkaClients() {}

    static Producer<String, String> newProducer(KafkaTransportConfig config) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.putAll(config.extraProducerProps());
        return new KafkaProducer<>(props);
    }

    static KafkaConsumer<String, String> newConsumer(KafkaTransportConfig config, String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.putAll(config.extraConsumerProps());
        return new KafkaConsumer<>(props);
    }

    /** {@code lifecycle-kind}, {@code lifecycle-action}, {@code lifecycle-event-id}, {@code lifecycle-correlation-id}, {@code lifecycle-hop} and, when set, {@code lifecycle-deliver-at}. */
    static List<Header> headersFor(LifecycleEvent event) {
        List<Header> headers = new ArrayList<>();
        headers.add(header("lifecycle-kind", event.kind().name()));
        headers.add(header("lifecycle-action", event.action()));
        headers.add(header("lifecycle-event-id", event.eventId()));
        headers.add(header("lifecycle-correlation-id", event.causation().correlationId()));
        headers.add(header("lifecycle-hop", String.valueOf(event.causation().hop())));
        if (event.deliverAt() != null) {
            headers.add(header("lifecycle-deliver-at", event.deliverAt().toString()));
        }
        return headers;
    }

    static Header header(String key, String value) {
        return new RecordHeader(key, value.getBytes(StandardCharsets.UTF_8));
    }
}
