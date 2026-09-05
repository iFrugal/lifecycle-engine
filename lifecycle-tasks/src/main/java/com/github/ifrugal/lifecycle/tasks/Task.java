package com.github.ifrugal.lifecycle.tasks;

import com.github.ifrugal.lifecycle.api.model.Causation;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.Payloads;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Work waiting on someone (DD-10). Created from a {@code lifecycle.task.create} notification, completed later by an
 * actor holding one of {@code assignTo}, and completion raises {@code onComplete} as a signal to {@code target},
 * which may be a different entity than {@code createdBy}: the brief's deferred cross-entity signal.
 *
 * @param causation the creating notification's causation, so the completion signal continues the chain (H6)
 */
public record Task(
        String taskId,
        String tenantId,
        String name,
        Set<String> assignTo,
        EntityRef createdBy,
        String createdByEventId,
        OnComplete onComplete,
        Map<String, Object> payload,
        Status status,
        String claimedBy,
        Instant createdAt,
        Instant updatedAt,
        Causation causation) {

    public enum Status { OPEN, CLAIMED, COMPLETED, CANCELLED }

    public record OnComplete(String action, EntityRef target) {
        public OnComplete {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(target, "target");
        }
    }

    public Task {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdByEventId, "createdByEventId");
        Objects.requireNonNull(onComplete, "onComplete");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(causation, "causation");
        assignTo = assignTo == null ? Set.of() : Set.copyOf(assignTo);
        payload = Payloads.immutable(payload);
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public boolean isOpen() {
        return status == Status.OPEN || status == Status.CLAIMED;
    }

    public Task with(Status newStatus, String newClaimedBy, Instant at) {
        return new Task(taskId, tenantId, name, assignTo, createdBy, createdByEventId, onComplete, payload, newStatus, newClaimedBy, createdAt, at, causation);
    }
}
