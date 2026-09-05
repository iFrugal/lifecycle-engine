package com.github.ifrugal.lifecycle.api.rules;

import java.util.Set;

/**
 * Work waiting on someone (DD-10). Compiles to a {@code lifecycle.task.create} notification. The
 * {@code onComplete} signal is raised by the tasks module when the task completes; its target defaults to the
 * creating entity.
 */
public record TaskDocument(String name, Set<String> assignTo, String onCompleteAction, TargetDocument onCompleteTarget, Object payload) {

    public TaskDocument {
        assignTo = assignTo == null ? Set.of() : Set.copyOf(assignTo);
    }
}
