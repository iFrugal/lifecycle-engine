package com.github.ifrugal.lifecycle.api.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One attempt, matched or not (R8). Refusals carry {@code reason} and {@code detail}; applied rows carry
 * {@code transitionId} and {@code toState}. {@code ruleSetVersion} is the snapshot the decision was made under.
 */
public record AuditRecord(
        String auditId,
        String eventId,
        EntityRef entity,
        String action,
        Actor actor,
        String fromState,
        String toState,
        String transitionId,
        AuditOutcome outcome,
        RefusalReason reason,
        String detail,
        String ruleSetVersion,
        Instant at,
        Causation causation) {

    public AuditRecord {
        Objects.requireNonNull(auditId, "auditId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(at, "at");
    }
}
