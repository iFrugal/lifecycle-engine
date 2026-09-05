package com.github.ifrugal.lifecycle.api.model;

import java.util.List;
import java.util.Objects;

/** What happened when the engine handled an event. Never null, never a boolean (H9). */
public sealed interface Outcome permits Outcome.Applied, Outcome.Refused, Outcome.Conflicted, Outcome.Duplicate {

    /** The entity moved (or a self-loop was applied) and {@code emitted} left the engine or re-entered it. */
    record Applied(String transitionId, String from, String to, long version, List<LifecycleEvent> emitted) implements Outcome {
        public Applied {
            Objects.requireNonNull(transitionId, "transitionId");
            emitted = emitted == null ? List.of() : List.copyOf(emitted);
        }
    }

    /** Nothing moved. Audited with the same reason and detail. */
    record Refused(RefusalReason reason, String detail) implements Outcome {
        public Refused {
            Objects.requireNonNull(reason, "reason");
            detail = detail == null ? "" : detail;
        }
    }

    /** The entity changed under us. Retried by the dispatcher unless the caller pinned {@code expectedVersion}. */
    record Conflicted(long expected, long actual) implements Outcome {}

    /** The same event id was already committed for this entity (R10). */
    record Duplicate(String firstAuditId) implements Outcome {}
}
