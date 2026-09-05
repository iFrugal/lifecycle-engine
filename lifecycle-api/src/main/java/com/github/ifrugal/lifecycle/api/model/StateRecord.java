package com.github.ifrugal.lifecycle.api.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Where an entity currently sits, and the version the next commit must expect (DD-07). An entity the store has
 * never seen is in the machine's initial state at version 0; its first applied commit creates the record.
 */
public record StateRecord(EntityRef ref, String state, long version, Instant updatedAt, String lastEventId, String ruleSetVersion) {

    public StateRecord {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(state, "state");
    }

    public static StateRecord initial(EntityRef ref, String initialState) {
        return new StateRecord(ref, initialState, 0L, null, null, null);
    }
}
