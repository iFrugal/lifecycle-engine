package com.github.ifrugal.lifecycle.core.rules;

/**
 * What a transition's {@code from} matches. Specificity orders candidates: exact beats a prefix, a deeper
 * prefix beats a shallower one, and {@code *} loses to everything (DD-03).
 */
public sealed interface StatePattern permits StatePattern.Any, StatePattern.Prefix, StatePattern.Exact {

    String WILDCARD = "*";
    String PREFIX_SUFFIX = ".*";

    boolean matches(StateName state);

    int specificity();

    String text();

    static StatePattern parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("from must not be blank");
        }
        if (WILDCARD.equals(text)) {
            return new Any();
        }
        if (text.endsWith(PREFIX_SUFFIX)) {
            return new Prefix(text.substring(0, text.length() - PREFIX_SUFFIX.length()));
        }
        return new Exact(StateName.of(text));
    }

    record Any() implements StatePattern {
        @Override public boolean matches(StateName state) { return true; }
        @Override public int specificity() { return 0; }
        @Override public String text() { return WILDCARD; }
    }

    record Prefix(String prefix) implements StatePattern {
        @Override public boolean matches(StateName state) { return state.under(prefix); }
        @Override public int specificity() { return 1 + prefix.split("\\.").length; }
        @Override public String text() { return prefix + PREFIX_SUFFIX; }
    }

    record Exact(StateName state) implements StatePattern {
        @Override public boolean matches(StateName s) { return state.equals(s); }
        @Override public int specificity() { return Integer.MAX_VALUE; }
        @Override public String text() { return state.value(); }
    }
}
