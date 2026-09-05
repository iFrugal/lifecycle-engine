package com.github.ifrugal.lifecycle.transport.kafka;

/** Wraps a checked or unexpected failure from the underlying Kafka client as an unchecked one (the {@code Transport} SPI declares no checked exceptions). */
public final class KafkaTransportException extends RuntimeException {

    public KafkaTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
