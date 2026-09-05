package com.github.ifrugal.lifecycle.core.rules;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** DD-03 guard `when` matching: literal / list / exists, total evaluation (H2), numeric equality, disjointness. */
class WhenMatcherTest {

    @Test
    void emptyOrNullWhenAlwaysHolds() {
        assertThat(WhenMatcher.holds(Map.of(), Map.of("a", 1))).isTrue();
        assertThat(WhenMatcher.holds(null, Map.of("a", 1))).isTrue();
    }

    @Test
    void literalEqualityMatchesOnValue() {
        Map<String, Object> when = Map.of("payment.status", "AUTHORISED");
        assertThat(WhenMatcher.holds(when, Map.of("payment", Map.of("status", "AUTHORISED")))).isTrue();
        assertThat(WhenMatcher.holds(when, Map.of("payment", Map.of("status", "DECLINED")))).isFalse();
    }

    @Test
    void absentPayloadPathFailsALiteralCondition() {
        Map<String, Object> when = Map.of("a.b", "x");
        assertThat(WhenMatcher.holds(when, Map.of())).isFalse();
    }

    @Test
    void listConditionIsAnyOf() {
        Map<String, Object> when = Map.of("kind", List.of("A", "B"));
        assertThat(WhenMatcher.holds(when, Map.of("kind", "A"))).isTrue();
        assertThat(WhenMatcher.holds(when, Map.of("kind", "B"))).isTrue();
        assertThat(WhenMatcher.holds(when, Map.of("kind", "C"))).isFalse();
        assertThat(WhenMatcher.holds(when, Map.of())).isFalse();
    }

    @Test
    void existsTrueRequiresPresence() {
        Map<String, Object> when = Map.of("a", Map.of("exists", true));
        assertThat(WhenMatcher.holds(when, Map.of("a", "anything"))).isTrue();
        assertThat(WhenMatcher.holds(when, Map.of())).isFalse();
    }

    @Test
    void existsFalseRequiresAbsence() {
        Map<String, Object> when = Map.of("a", Map.of("exists", false));
        assertThat(WhenMatcher.holds(when, Map.of())).isTrue();
        assertThat(WhenMatcher.holds(when, Map.of("a", "anything"))).isFalse();
    }

    @Test
    void evaluationIsTotalOverEveryEntryNoEarlyReturn() {
        // H2: even though the first entry alone would already fail the match, a second, satisfied entry
        // must not "short circuit" a true result in, and a failing entry anywhere must fail the whole map.
        Map<String, Object> when = new java.util.LinkedHashMap<>();
        when.put("missing", "x");   // fails
        when.put("present", "y");  // holds
        assertThat(WhenMatcher.holds(when, Map.of("present", "y"))).isFalse();

        Map<String, Object> allHold = new java.util.LinkedHashMap<>();
        allHold.put("a", "1");
        allHold.put("b", "2");
        allHold.put("c", Map.of("exists", false));
        assertThat(WhenMatcher.holds(allHold, Map.of("a", "1", "b", "2"))).isTrue();
    }

    @Test
    void numbersCompareByValueAcrossTypes() {
        assertThat(WhenMatcher.literalEquals(1, 1.0)).isTrue();
        assertThat(WhenMatcher.literalEquals(1, 1L)).isTrue();
        assertThat(WhenMatcher.literalEquals(1, 2)).isFalse();
        assertThat(WhenMatcher.literalEquals("1", 1)).isFalse();
    }

    @Test
    void disjointRequiresACommonPathWithNonIntersectingLiterals() {
        assertThat(WhenMatcher.disjoint(Map.of("kind", "A"), Map.of("kind", "B"))).isTrue();
        assertThat(WhenMatcher.disjoint(Map.of("kind", "A"), Map.of("kind", "A"))).isFalse();
        assertThat(WhenMatcher.disjoint(Map.of("kind", List.of("A", "B")), Map.of("kind", "C"))).isTrue();
        assertThat(WhenMatcher.disjoint(Map.of("kind", "A"), Map.of("kind", List.of("A", "C")))).isFalse();
        // no common path at all -> not provably disjoint
        assertThat(WhenMatcher.disjoint(Map.of("kind", "A"), Map.of("other", "B"))).isFalse();
    }

    @Test
    void disjointHandlesExistsConditions() {
        assertThat(WhenMatcher.disjoint(Map.of("a", Map.of("exists", true)), Map.of("a", Map.of("exists", false)))).isTrue();
        assertThat(WhenMatcher.disjoint(Map.of("a", Map.of("exists", true)), Map.of("a", Map.of("exists", true)))).isFalse();
        // exists:false vs a literal value requirement: absence vs a value are disjoint
        assertThat(WhenMatcher.disjoint(Map.of("a", Map.of("exists", false)), Map.of("a", "X"))).isTrue();
    }

    @Test
    void nullMapsAreNotProvablyDisjoint() {
        assertThat(WhenMatcher.disjoint(null, Map.of("a", "b"))).isFalse();
        assertThat(WhenMatcher.disjoint(Map.of("a", "b"), null)).isFalse();
    }
}
