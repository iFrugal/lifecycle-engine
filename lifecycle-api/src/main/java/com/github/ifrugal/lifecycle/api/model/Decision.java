package com.github.ifrugal.lifecycle.api.model;

import java.util.List;
import java.util.Objects;

/** The pure result of (state, event, definition): what would happen, with no side effects (R9). */
public sealed interface Decision permits Decision.Match, Decision.Refuse {

    record Match(TransitionView transition, String from, String to, List<Emission> emissions) implements Decision {
        public Match {
            Objects.requireNonNull(transition, "transition");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            emissions = emissions == null ? List.of() : List.copyOf(emissions);
        }
    }

    record Refuse(RefusalReason reason, String detail) implements Decision {
        public Refuse {
            Objects.requireNonNull(reason, "reason");
            detail = detail == null ? "" : detail;
        }
    }
}
