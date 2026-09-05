package com.github.ifrugal.lifecycle.core.rules;

import com.github.ifrugal.lifecycle.api.model.TransitionView;
import com.github.ifrugal.lifecycle.api.rules.TaskDocument;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** A validated edge. Immutable; collections are copies. */
public record CompiledTransition(
        String id,
        StatePattern from,
        Set<StateName> except,
        String on,
        Set<String> roles,
        Map<String, Object> when,
        String guard,
        StateName to,
        List<CompiledEmit> emit,
        TaskDocument task) {

    public CompiledTransition {
        except = Set.copyOf(except);
        roles = Set.copyOf(roles);
        when = Map.copyOf(when);
        emit = List.copyOf(emit);
    }

    public boolean appliesTo(StateName state) {
        return from.matches(state) && !except.contains(state);
    }

    public int specificity() {
        return from.specificity();
    }

    public TransitionView view() {
        return new TransitionView(id, from.text(), except.stream().map(StateName::value).sorted().toList(), on, roles, when, guard, to.value());
    }
}
