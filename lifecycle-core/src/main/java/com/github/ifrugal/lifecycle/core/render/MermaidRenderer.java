package com.github.ifrugal.lifecycle.core.render;

import com.github.ifrugal.lifecycle.core.rules.CompiledEmit;
import com.github.ifrugal.lifecycle.core.rules.CompiledTransition;
import com.github.ifrugal.lifecycle.core.rules.Machine;
import com.github.ifrugal.lifecycle.core.rules.StateName;
import com.github.ifrugal.lifecycle.core.rules.StatePattern;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Renders a compiled {@link Machine} to a Mermaid state diagram.
 */
public final class MermaidRenderer {

    private MermaidRenderer() {}

    /**
     * Renders a machine to a Mermaid stateDiagram-v2 format that a non-programmer can read.
     */
    public static String render(Machine machine) {
        StringBuilder sb = new StringBuilder();
        sb.append("stateDiagram-v2\n");

        // Declare state aliases for dotted names
        Set<StateName> dottedStates = machine.states().stream()
                .filter(s -> s.value().contains("."))
                .collect(Collectors.toCollection(() -> new TreeSet<>(java.util.Comparator.comparing(StateName::value))));

        for (StateName state : dottedStates) {
            String alias = stateIdAlias(state.value());
            sb.append("    state \"").append(state.value()).append("\" as ").append(alias).append("\n");
        }

        // Initial transition
        sb.append("    [*] --> ").append(stateId(machine.initial().value())).append("\n");

        // Collect all edges
        Set<String> edgeLines = new LinkedHashSet<>();
        Set<StateName> reachableStates = new TreeSet<>(java.util.Comparator.comparing(StateName::value));

        for (CompiledTransition transition : machine.transitions()) {
            // For each state the transition applies to
            for (StateName state : machine.states().stream()
                    .filter(s -> transition.appliesTo(s))
                    .collect(Collectors.toCollection(() -> new TreeSet<>(java.util.Comparator.comparing(StateName::value))))) {
                reachableStates.add(state);
                String edgeLine = buildEdgeLine(state, transition);
                edgeLines.add(edgeLine);
            }
        }

        // Add edges to output
        for (String edgeLine : edgeLines) {
            sb.append("    ").append(edgeLine).append("\n");
        }

        // Add terminal state edges (states with no outgoing transitions)
        Set<StateName> statesWithOutgoing = new TreeSet<>(java.util.Comparator.comparing(StateName::value));
        for (CompiledTransition transition : machine.transitions()) {
            for (StateName state : machine.states().stream()
                    .filter(s -> transition.appliesTo(s))
                    .collect(Collectors.toSet())) {
                statesWithOutgoing.add(state);
            }
        }

        for (StateName state : machine.states().stream()
                .filter(s -> !statesWithOutgoing.contains(s))
                .collect(Collectors.toCollection(() -> new TreeSet<>(java.util.Comparator.comparing(StateName::value))))) {
            sb.append("    ").append(stateId(state.value())).append(" --> [*]\n");
        }

        return sb.toString();
    }

    /**
     * Builds an edge line for a transition from a specific state.
     */
    private static String buildEdgeLine(StateName fromState, CompiledTransition transition) {
        StringBuilder label = new StringBuilder();

        // Add action
        label.append(transition.on());

        // Add roles if non-empty
        if (!transition.roles().isEmpty()) {
            label.append(" [");
            label.append(transition.roles().stream()
                    .sorted()
                    .collect(Collectors.joining(", ")));
            label.append("]");
        }

        // Add when conditions if non-empty
        if (!transition.when().isEmpty()) {
            if (label.length() > 0) {
                label.append(" ");
            }
            label.append(transition.when().entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(" & ")));
        }

        // Add guard if set
        if (transition.guard() != null && !transition.guard().isBlank()) {
            if (label.length() > 0) {
                label.append(" ");
            }
            label.append("guard:").append(transition.guard().replace(":", "-"));
        }

        // Add emits if any (skip lifecycle.task.create)
        if (!transition.emit().isEmpty()) {
            StringBuilder emits = new StringBuilder();
            for (CompiledEmit emit : transition.emit()) {
                if (!emit.action().equals("lifecycle.task.create")) {
                    if (emits.length() > 0) {
                        emits.append(",");
                    }
                    emits.append(emit.action());
                }
            }
            // Add task emit if task is present
            if (transition.task() != null) {
                if (emits.length() > 0) {
                    emits.append(",");
                }
                emits.append("task:").append(transition.task().name());
            }
            if (emits.length() > 0) {
                if (label.length() > 0) {
                    label.append(" ");
                }
                label.append("→ emits ").append(emits);
            }
        } else if (transition.task() != null) {
            // Task without other emits
            if (label.length() > 0) {
                label.append(" ");
            }
            label.append("→ emits task:").append(transition.task().name());
        }

        // Escape colons in label
        String finalLabel = label.toString().replace(":", "-");

        return stateId(fromState.value()) + " --> " + stateId(transition.to().value()) +
                (finalLabel.isEmpty() ? "" : " : " + finalLabel);
    }

    /**
     * Gets the state ID (uses alias if state name contains dots).
     */
    private static String stateId(String stateName) {
        if (stateName.contains(".")) {
            return stateIdAlias(stateName);
        }
        return stateName;
    }

    /**
     * Converts a state name to a valid Mermaid state alias (replaces dots with underscores).
     */
    private static String stateIdAlias(String stateName) {
        return stateName.replace(".", "_");
    }
}
