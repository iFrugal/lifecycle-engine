package com.github.ifrugal.lifecycle.core.rules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** One compiled, immutable machine for one (tenant, entity type). Loaded rules cannot be mutated by anyone (H8). */
public record Machine(
        String tenantId,
        String entityType,
        StateName initial,
        int maxHops,
        Set<StateName> states,
        List<CompiledTransition> transitions,
        Map<String, List<CompiledTransition>> byAction) {

    public Machine {
        states = Set.copyOf(states);
        transitions = List.copyOf(transitions);
        byAction = Map.copyOf(byAction);
    }

    public static Machine of(String tenantId, String entityType, StateName initial, int maxHops, Set<StateName> states, List<CompiledTransition> transitions) {
        LinkedHashMap<String, List<CompiledTransition>> index = new LinkedHashMap<>();
        for (CompiledTransition t : transitions) {
            index.computeIfAbsent(t.on(), k -> new java.util.ArrayList<>()).add(t);
        }
        Map<String, List<CompiledTransition>> frozen = new LinkedHashMap<>();
        index.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        return new Machine(tenantId, entityType, initial, maxHops, states, transitions, frozen);
    }

    public List<CompiledTransition> forAction(String action) {
        return byAction.getOrDefault(action, List.of());
    }

    public Set<String> actions() {
        return new TreeSet<>(byAction.keySet());
    }
}
