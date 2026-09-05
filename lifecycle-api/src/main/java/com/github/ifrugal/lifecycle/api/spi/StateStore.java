package com.github.ifrugal.lifecycle.api.spi;

import com.github.ifrugal.lifecycle.api.model.AuditRecord;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.StateRecord;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * The storage seam (R7). Implementations own atomicity: {@link #commit} must apply the inbox check, the version
 * check, the audit append, the state write and the outbox append all-or-nothing. See DD-07 and DD-11.
 */
public interface StateStore {

    Optional<StateRecord> find(EntityRef ref);

    /**
     * Atomic. Order of checks: inbox first (a replay is a replay regardless of version), then version.
     */
    CommitResult commit(Commit commit);

    /** Append an audit row not tied to a version, used for conflicts so contention is visible (R12). */
    void appendDetached(AuditRecord record);

    /** Optional capability: how many entities of this type sit in this state. Drives orphan detection (DD-06). */
    default OptionalLong countInState(String tenantId, String entityType, String state) {
        return OptionalLong.empty();
    }

    /** The outbox this store writes into, if it exposes one for the engine to mark sent and a relay to drain. */
    default Optional<Outbox> outbox() {
        return Optional.empty();
    }
}
