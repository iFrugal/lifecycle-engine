package com.github.ifrugal.lifecycle.api.rules;

import java.util.List;
import java.util.stream.Collectors;

/** Thrown when a rule set, or a whole snapshot, cannot be loaded. Carries every problem, not just the first. */
public class RuleSetValidationException extends RuntimeException {

    private final List<Problem> problems;

    public RuleSetValidationException(List<Problem> problems) {
        super(problems.size() + " problem(s):\n" + problems.stream().map(Problem::toString).collect(Collectors.joining("\n")));
        this.problems = List.copyOf(problems);
    }

    public List<Problem> problems() {
        return problems;
    }
}
