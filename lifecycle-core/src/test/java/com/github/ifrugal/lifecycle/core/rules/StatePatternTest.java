package com.github.ifrugal.lifecycle.core.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** DD-03 specificity: exact beats a deeper prefix beats a shallower prefix beats `*`. */
class StatePatternTest {

    @Test
    void parsesWildcard() {
        StatePattern p = StatePattern.parse("*");
        assertThat(p).isInstanceOf(StatePattern.Any.class);
        assertThat(p.text()).isEqualTo("*");
        assertThat(p.matches(StateName.of("ANYTHING"))).isTrue();
    }

    @Test
    void parsesPrefix() {
        StatePattern p = StatePattern.parse("ACTIVE.*");
        assertThat(p).isInstanceOf(StatePattern.Prefix.class);
        assertThat(p.text()).isEqualTo("ACTIVE.*");
    }

    @Test
    void parsesExact() {
        StatePattern p = StatePattern.parse("PAID");
        assertThat(p).isInstanceOf(StatePattern.Exact.class);
        assertThat(p.text()).isEqualTo("PAID");
    }

    @Test
    void exactMatchesOnlyItself() {
        StatePattern p = StatePattern.parse("PAID");
        assertThat(p.matches(StateName.of("PAID"))).isTrue();
        assertThat(p.matches(StateName.of("PAID2"))).isFalse();
        assertThat(p.matches(StateName.of("NEW"))).isFalse();
    }

    @Test
    void prefixMatchesItselfAndDescendantsOnly() {
        StatePattern p = StatePattern.parse("ACTIVE.*");
        assertThat(p.matches(StateName.of("ACTIVE"))).isTrue();
        assertThat(p.matches(StateName.of("ACTIVE.SUSPENDED"))).isTrue();
        assertThat(p.matches(StateName.of("ACTIVE.SUSPENDED.DEEP"))).isTrue();
        // must not match a state that merely shares the prefix as a string, without the dot boundary
        assertThat(p.matches(StateName.of("ACTIVATED"))).isFalse();
    }

    @Test
    void specificityOrdersExactOverPrefixOverAny() {
        StatePattern any = StatePattern.parse("*");
        StatePattern shallow = StatePattern.parse("A.*");
        StatePattern deeper = StatePattern.parse("A.B.*");
        StatePattern exact = StatePattern.parse("A.B.C");

        assertThat(exact.specificity()).isGreaterThan(deeper.specificity());
        assertThat(deeper.specificity()).isGreaterThan(shallow.specificity());
        assertThat(shallow.specificity()).isGreaterThan(any.specificity());
    }

    @Test
    void blankFromIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> StatePattern.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> StatePattern.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
