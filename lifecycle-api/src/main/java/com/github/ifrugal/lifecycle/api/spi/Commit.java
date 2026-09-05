package com.github.ifrugal.lifecycle.api.spi;

import com.github.ifrugal.lifecycle.api.model.AuditRecord;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;

import java.util.List;
import java.util.Objects;

/**
 * One atomic unit of work for a {@link StateStore} (DD-07). {@code nextState == null} means "no state change and
 * no version bump" and is used to record refusals in the inbox and the audit.
 */
public record Commit(EntityRef ref, long expectedVersion, String nextState, String eventId, AuditRecord audit, List<LifecycleEvent> outbox) {

    public Commit {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(audit, "audit");
        outbox = outbox == null ? List.of() : List.copyOf(outbox);
    }

    public boolean advances() {
        return nextState != null;
    }
}
