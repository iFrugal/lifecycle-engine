package com.github.ifrugal.lifecycle.core.rules;

import com.github.ifrugal.lifecycle.api.rules.Problem;
import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;
import com.github.ifrugal.lifecycle.api.rules.TransitionDocument;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Merges a tenant overlay onto a base by transition id: same id replaces whole, {@code disabled} removes, new id
 * adds. States are additive. Fields are never deep-merged (DD-05).
 */
public final class OverlayMerger {

    private OverlayMerger() {}

    public record Result(RuleSetDocument merged, List<Problem> problems) {
        public boolean ok() {
            return problems.isEmpty();
        }
    }

    public static Result merge(RuleSetDocument base, RuleSetDocument overlay) {
        List<Problem> problems = new ArrayList<>();
        if (!overlay.isOverlay()) {
            problems.add(new Problem(null, overlay.entityType(), null, "not an overlay: tenantId is absent"));
        }
        if (!base.entityType().equals(overlay.entityType())) {
            problems.add(new Problem(overlay.tenantId(), overlay.entityType(), null, "overlay entity type differs from base " + base.entityType()));
        }

        LinkedHashSet<String> states = new LinkedHashSet<>(base.states());
        states.addAll(overlay.states());

        LinkedHashMap<String, TransitionDocument> transitions = new LinkedHashMap<>();
        for (TransitionDocument t : base.transitions()) {
            transitions.put(t.id(), t);
        }
        int index = 0;
        for (TransitionDocument t : overlay.transitions()) {
            index++;
            if (t.id() == null || t.id().isBlank()) {
                problems.add(new Problem(overlay.tenantId(), overlay.entityType(), "#" + index, "overlay transition needs an id"));
                continue;
            }
            if (t.disabled()) {
                if (transitions.remove(t.id()) == null) {
                    problems.add(new Problem(overlay.tenantId(), overlay.entityType(), t.id(), "disables a transition the base does not have"));
                }
            } else {
                transitions.put(t.id(), t);
            }
        }

        String initial = overlay.initial() != null ? overlay.initial() : base.initial();
        Integer maxHops = overlay.maxHops() != null ? overlay.maxHops() : base.maxHops();
        RuleSetDocument merged = new RuleSetDocument(overlay.tenantId(), base.entityType(), initial, maxHops,
                new ArrayList<>(states), new ArrayList<>(transitions.values()));
        return new Result(merged, List.copyOf(problems));
    }
}
