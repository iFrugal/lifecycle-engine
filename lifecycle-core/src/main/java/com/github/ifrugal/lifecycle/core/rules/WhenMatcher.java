package com.github.ifrugal.lifecycle.core.rules;

import com.github.ifrugal.lifecycle.api.model.Payloads;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Subset matching of a {@code when} map against a payload (DD-03). Evaluation is total: every entry is
 * evaluated and the result is their conjunction, with no early return (H2).
 */
public final class WhenMatcher {

    public static final String EXISTS = "exists";

    private WhenMatcher() {}

    public static boolean holds(Map<String, Object> when, Map<String, Object> payload) {
        if (when == null || when.isEmpty()) {
            return true;
        }
        boolean result = true;
        for (Map.Entry<String, Object> e : when.entrySet()) {
            boolean h = entryHolds(e.getKey(), e.getValue(), payload);
            result = result & h; // non-short-circuit on purpose: total evaluation (H2)
        }
        return result;
    }

    static boolean entryHolds(String path, Object expected, Map<String, Object> payload) {
        Optional<Object> actual = Payloads.path(payload, path);
        if (isExists(expected)) {
            boolean mustExist = Boolean.TRUE.equals(((Map<?, ?>) expected).get(EXISTS));
            return actual.isPresent() == mustExist;
        }
        if (expected instanceof Collection<?> anyOf) {
            return actual.isPresent() && anyOf.stream().anyMatch(x -> literalEquals(x, actual.get()));
        }
        return actual.isPresent() && literalEquals(expected, actual.get());
    }

    static boolean isExists(Object expected) {
        return expected instanceof Map<?, ?> m && m.size() == 1 && m.containsKey(EXISTS);
    }

    /** Numbers compare by value ({@code 1 == 1.0 == 1L}); everything else by {@code equals}. */
    public static boolean literalEquals(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            return new BigDecimal(na.toString()).compareTo(new BigDecimal(nb.toString())) == 0;
        }
        return Objects.equals(a, b);
    }

    /**
     * Provably disjoint: some path present in both maps has literal sets that do not intersect, or opposite
     * {@code exists} conditions. Guards and roles never count (DD-03).
     */
    public static boolean disjoint(Map<String, Object> a, Map<String, Object> b) {
        if (a == null || b == null) {
            return false;
        }
        Set<String> common = new HashSet<>(a.keySet());
        common.retainAll(b.keySet());
        for (String path : common) {
            Object va = a.get(path);
            Object vb = b.get(path);
            if (isExists(va) || isExists(vb)) {
                if (isExists(va) && isExists(vb)) {
                    boolean ea = Boolean.TRUE.equals(((Map<?, ?>) va).get(EXISTS));
                    boolean eb = Boolean.TRUE.equals(((Map<?, ?>) vb).get(EXISTS));
                    if (ea != eb) {
                        return true;
                    }
                } else {
                    Object exists = isExists(va) ? va : vb;
                    if (Boolean.FALSE.equals(((Map<?, ?>) exists).get(EXISTS))) {
                        return true; // one requires absence, the other a value
                    }
                }
                continue;
            }
            List<Object> la = literals(va);
            List<Object> lb = literals(vb);
            boolean intersects = la.stream().anyMatch(x -> lb.stream().anyMatch(y -> literalEquals(x, y)));
            if (!intersects) {
                return true;
            }
        }
        return false;
    }

    private static List<Object> literals(Object v) {
        if (v instanceof Collection<?> c) {
            return List.copyOf(c);
        }
        return List.of(v);
    }
}
