package com.github.ifrugal.lifecycle.api.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The single currency of the system: what the engine consumes and what it emits (R2). See DD-02.
 *
 * @param eventId          dedupe key
 * @param kind             signal or notification
 * @param entity           whom it concerns
 * @param action           what happened; reused across states, never identifies an edge alone
 * @param actor            who did it, with roles
 * @param payload          JSON-shaped, deeply immutable
 * @param occurredAt       when
 * @param deliverAt        when to deliver, for timers; null otherwise
 * @param causation        chain and hop count
 * @param expectedVersion  optional optimistic precondition set by a caller (typically a UI)
 */
public record LifecycleEvent(
        String eventId,
        EventKind kind,
        EntityRef entity,
        String action,
        Actor actor,
        Map<String, Object> payload,
        Instant occurredAt,
        Instant deliverAt,
        Causation causation,
        Long expectedVersion) {

    public LifecycleEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(occurredAt, "occurredAt");
        payload = Payloads.immutable(payload);
        if (causation == null) {
            causation = Causation.root(eventId);
        }
    }

    public boolean isSignal() {
        return kind == EventKind.SIGNAL;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.eventId = eventId;
        b.kind = kind;
        b.entity = entity;
        b.action = action;
        b.actor = actor;
        b.payload = payload;
        b.occurredAt = occurredAt;
        b.deliverAt = deliverAt;
        b.causation = causation;
        b.expectedVersion = expectedVersion;
        return b;
    }

    public static final class Builder {
        private String eventId;
        private EventKind kind = EventKind.SIGNAL;
        private EntityRef entity;
        private String action;
        private Actor actor;
        private Map<String, Object> payload;
        private Instant occurredAt;
        private Instant deliverAt;
        private Causation causation;
        private Long expectedVersion;

        private Builder() {}

        public Builder eventId(String v) { this.eventId = v; return this; }
        public Builder kind(EventKind v) { this.kind = v; return this; }
        public Builder entity(EntityRef v) { this.entity = v; return this; }
        public Builder action(String v) { this.action = v; return this; }
        public Builder actor(Actor v) { this.actor = v; return this; }
        public Builder payload(Map<String, ?> v) { this.payload = v == null ? null : Payloads.immutable(v); return this; }
        public Builder occurredAt(Instant v) { this.occurredAt = v; return this; }
        public Builder deliverAt(Instant v) { this.deliverAt = v; return this; }
        public Builder causation(Causation v) { this.causation = v; return this; }
        public Builder expectedVersion(Long v) { this.expectedVersion = v; return this; }

        public LifecycleEvent build() {
            String id = eventId == null ? UUID.randomUUID().toString() : eventId;
            Instant at = occurredAt == null ? Instant.now() : occurredAt;
            return new LifecycleEvent(id, kind, entity, action, actor, payload, at, deliverAt, causation, expectedVersion);
        }
    }
}
