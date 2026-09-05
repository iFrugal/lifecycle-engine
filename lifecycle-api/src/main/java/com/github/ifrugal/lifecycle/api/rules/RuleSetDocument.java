package com.github.ifrugal.lifecycle.api.rules;

import java.util.List;
import java.util.Objects;

/**
 * One machine as declared: a base (no {@code tenantId}) or a tenant overlay (DD-05). Parsed from YAML/JSON or
 * built in code; compiled by the core.
 */
public record RuleSetDocument(String tenantId, String entityType, String initial, Integer maxHops, List<String> states, List<TransitionDocument> transitions) {

    public RuleSetDocument {
        Objects.requireNonNull(entityType, "entityType");
        if (tenantId != null && tenantId.isBlank()) {
            tenantId = null;
        }
        states = states == null ? List.of() : List.copyOf(states);
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
    }

    public boolean isOverlay() {
        return tenantId != null;
    }
}
