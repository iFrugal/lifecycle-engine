package com.github.ifrugal.lifecycle.api.model;

import java.util.Objects;

/**
 * Where an event came from. {@code correlationId} is the root of the chain, {@code causationId} the event that
 * directly produced this one (null at the root), {@code hop} the distance from the root. The hop count is what
 * lets a cascade terminate after it has crossed a broker (H6).
 */
public record Causation(String correlationId, String causationId, int hop) {

    public Causation {
        Objects.requireNonNull(correlationId, "correlationId");
        if (hop < 0) {
            throw new IllegalArgumentException("hop must be >= 0");
        }
    }

    public static Causation root(String eventId) {
        return new Causation(eventId, null, 0);
    }

    /** The causation of an event produced while consuming {@code parentEventId}. */
    public Causation child(String parentEventId) {
        return new Causation(correlationId, parentEventId, hop + 1);
    }
}
