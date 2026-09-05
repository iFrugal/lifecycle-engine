package com.github.ifrugal.lifecycle.core.hazards;

import com.github.ifrugal.lifecycle.api.model.Payloads;
import com.github.ifrugal.lifecycle.core.rules.WhenMatcher;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2: partial guard evaluation. {@code when} is a fold over every entry with no early return, so the verdict
 * cannot depend on the order the entries happen to be written in. Every permutation of a four-entry map is
 * checked, each against a payload that violates exactly the entry that was inserted last.
 */
class H2WhenMatcherTotalTest {

    /** The four conditions: a nested path, an any-of list, an existence check and a number. */
    private static final List<String> PATHS = List.of("a.b", "status", "flag", "n");

    private static Object condition(String path) {
        return switch (path) {
            case "a.b" -> "X";
            case "status" -> List.of("P", "Q");
            case "flag" -> Map.of(WhenMatcher.EXISTS, true);
            case "n" -> 1;
            default -> throw new IllegalArgumentException(path);
        };
    }

    /** A payload satisfying every condition. {@code n} is 1.0 so numeric equality is exercised throughout. */
    private static Map<String, Object> satisfyingPayload() {
        LinkedHashMap<String, Object> p = new LinkedHashMap<>();
        p.put("a", new LinkedHashMap<>(Map.of("b", "X")));
        p.put("status", "P");
        p.put("flag", "anything");
        p.put("n", 1.0d);
        return Payloads.immutable(p);
    }

    /** The satisfying payload with exactly one condition broken. */
    private static Map<String, Object> payloadViolating(String path) {
        LinkedHashMap<String, Object> p = new LinkedHashMap<>(satisfyingPayload());
        switch (path) {
            case "a.b" -> p.put("a", new LinkedHashMap<>(Map.of("b", "Y")));
            case "status" -> p.put("status", "Z");
            case "flag" -> p.remove("flag");
            case "n" -> p.put("n", 2);
            default -> throw new IllegalArgumentException(path);
        }
        return Payloads.immutable(p);
    }

    private static Map<String, Object> whenIn(List<String> order) {
        LinkedHashMap<String, Object> when = new LinkedHashMap<>();
        order.forEach(p -> when.put(p, condition(p)));
        return when;
    }

    private static <T> List<List<T>> permutations(List<T> items) {
        List<List<T>> out = new ArrayList<>();
        permute(new ArrayList<>(items), new ArrayList<>(), out);
        return out;
    }

    private static <T> void permute(List<T> remaining, List<T> prefix, List<List<T>> out) {
        if (remaining.isEmpty()) {
            out.add(List.copyOf(prefix));
            return;
        }
        for (int i = 0; i < remaining.size(); i++) {
            List<T> rest = new ArrayList<>(remaining);
            T head = rest.remove(i);
            prefix.add(head);
            permute(rest, prefix, out);
            prefix.remove(prefix.size() - 1);
        }
    }

    @Test
    void everyInsertionOrderAgrees() {
        List<List<String>> orders = permutations(PATHS);
        assertThat(orders).hasSize(24);

        for (List<String> order : orders) {
            Map<String, Object> when = whenIn(order);
            assertThat(when.keySet()).containsExactlyElementsOf(order);

            assertThat(WhenMatcher.holds(when, satisfyingPayload()))
                    .as("all conditions satisfied, insertion order %s", order)
                    .isTrue();

            String last = order.get(order.size() - 1);
            assertThat(WhenMatcher.holds(when, payloadViolating(last)))
                    .as("last-inserted entry '%s' violated, insertion order %s", last, order)
                    .isFalse();
        }
    }

    @Test
    void everyEntryCanBeTheSoleViolationRegardlessOfWhereItSits() {
        for (List<String> order : permutations(PATHS)) {
            Map<String, Object> when = whenIn(order);
            for (String violated : PATHS) {
                assertThat(WhenMatcher.holds(when, payloadViolating(violated)))
                        .as("entry '%s' violated, insertion order %s", violated, order)
                        .isFalse();
            }
        }
    }

    @Test
    void existsTrueAndExistsFalse() {
        Map<String, Object> mustExist = Map.of("flag", Map.of(WhenMatcher.EXISTS, true));
        Map<String, Object> mustNotExist = Map.of("flag", Map.of(WhenMatcher.EXISTS, false));

        Map<String, Object> present = Payloads.immutable(Map.of("flag", "anything"));
        Map<String, Object> absent = Payloads.immutable(Map.of("other", 1));

        assertThat(WhenMatcher.holds(mustExist, present)).isTrue();
        assertThat(WhenMatcher.holds(mustExist, absent)).isFalse();
        assertThat(WhenMatcher.holds(mustNotExist, present)).isFalse();
        assertThat(WhenMatcher.holds(mustNotExist, absent)).isTrue();

        // An absent nested path fails a literal and satisfies exists:false (DD-03).
        Map<String, Object> nestedMustNotExist = Map.of("a.b", Map.of(WhenMatcher.EXISTS, false));
        assertThat(WhenMatcher.holds(nestedMustNotExist, absent)).isTrue();
        assertThat(WhenMatcher.holds(Map.of("a.b", "X"), absent)).isFalse();

        // A non-map on the way to a nested path is "absent", not an error.
        Map<String, Object> scalarAtA = Payloads.immutable(Map.of("a", 7));
        assertThat(WhenMatcher.holds(nestedMustNotExist, scalarAtA)).isTrue();
        assertThat(WhenMatcher.holds(Map.of("a.b", "X"), scalarAtA)).isFalse();

        // A null payload is simply an empty one.
        assertThat(WhenMatcher.holds(mustExist, Payloads.immutable(null))).isFalse();
        assertThat(WhenMatcher.holds(mustNotExist, Payloads.immutable(null))).isTrue();
    }

    @Test
    void numbersCompareByValue() {
        assertThat(WhenMatcher.literalEquals(1, 1.0d)).isTrue();
        assertThat(WhenMatcher.literalEquals(1.0d, 1)).isTrue();
        assertThat(WhenMatcher.literalEquals(1, 1L)).isTrue();
        assertThat(WhenMatcher.literalEquals(1, new java.math.BigDecimal("1.000"))).isTrue();
        assertThat(WhenMatcher.literalEquals(1, 2)).isFalse();
        assertThat(WhenMatcher.literalEquals(1, "1")).isFalse();

        assertThat(WhenMatcher.holds(Map.of("n", 1), Payloads.immutable(Map.of("n", 1.0d)))).isTrue();
        assertThat(WhenMatcher.holds(Map.of("n", 1.0d), Payloads.immutable(Map.of("n", 1)))).isTrue();
        assertThat(WhenMatcher.holds(Map.of("n", 1), Payloads.immutable(Map.of("n", 1L)))).isTrue();
        assertThat(WhenMatcher.holds(Map.of("n", 1), Payloads.immutable(Map.of("n", 2)))).isFalse();
        assertThat(WhenMatcher.holds(Map.of("n", List.of(1, 3)), Payloads.immutable(Map.of("n", 3.0d)))).isTrue();
    }

    @Test
    void anEmptyOrAbsentWhenAlwaysHolds() {
        assertThat(WhenMatcher.holds(null, satisfyingPayload())).isTrue();
        assertThat(WhenMatcher.holds(Map.of(), satisfyingPayload())).isTrue();
        assertThat(WhenMatcher.holds(Map.of(), Payloads.immutable(null))).isTrue();
    }
}
