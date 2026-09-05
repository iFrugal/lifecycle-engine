package com.github.ifrugal.lifecycle.core.rules;

import java.util.Objects;

/**
 * A state name compared by value and nothing else (H1). Dotted names form a matching hierarchy only:
 * {@code ACTIVE.SUSPENDED} is under {@code ACTIVE}.
 */
public final class StateName implements Comparable<StateName> {

    private final String value;

    private StateName(String value) {
        this.value = value;
    }

    public static StateName of(String value) {
        Objects.requireNonNull(value, "state name");
        if (value.isBlank()) {
            throw new IllegalArgumentException("state name must not be blank");
        }
        return new StateName(value);
    }

    public String value() {
        return value;
    }

    /** True if this is {@code prefix} itself or a descendant {@code prefix.x...}. */
    public boolean under(String prefix) {
        return value.equals(prefix) || value.startsWith(prefix + ".");
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StateName s && s.value.equals(value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public int compareTo(StateName o) {
        return value.compareTo(o.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
