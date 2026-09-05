package com.github.ifrugal.lifecycle.core.rules;

import com.github.ifrugal.lifecycle.api.guard.GuardContext;
import com.github.ifrugal.lifecycle.api.guard.GuardPredicate;
import com.github.ifrugal.lifecycle.api.rules.EmitDocument;
import com.github.ifrugal.lifecycle.api.rules.Problem;
import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;
import com.github.ifrugal.lifecycle.api.rules.TargetDocument;
import com.github.ifrugal.lifecycle.api.rules.TransitionDocument;
import com.github.ifrugal.lifecycle.core.testdomain.SampleRules;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DD-03 static ambiguity check and the compiler's other load-time refusals/warnings (DD-04).
 */
class AmbiguityTest {

    private record NamedGuard(String n) implements GuardPredicate {
        @Override public String name() { return n; }
        @Override public boolean test(GuardContext context) { return true; }
    }

    private static RuleSetDocument doc(List<TransitionDocument> transitions) {
        return new RuleSetDocument(null, "widget", "A", null, List.of("A", "B", "C", "X", "X.Y"), transitions);
    }

    private RuleCompiler.Result compile(RuleSetDocument d) {
        return compile(d, GuardRegistry.empty());
    }

    private RuleCompiler.Result compile(RuleSetDocument d, GuardRegistry guards) {
        return new RuleCompiler().compile(d, guards, Set.of("widget"));
    }

    @Test
    void sameFromAndOnWithNoWhenIsAmbiguous() {
        RuleSetDocument d = doc(List.of(
                TransitionDocument.builder("t1").from("A").on("X").to("B").build(),
                TransitionDocument.builder("t2").from("A").on("X").to("C").build()));
        RuleCompiler.Result r = compile(d);
        assertThat(r.ok()).isFalse();
        assertThat(r.problems()).anySatisfy(p -> assertThat(p.message()).containsIgnoringCase("ambiguous"));
    }

    @Test
    void disjointLiteralWhensAreNotAmbiguous() {
        RuleSetDocument d = doc(List.of(
                TransitionDocument.builder("t1").from("A").on("X").when(SampleRules.map("kind", "A")).to("B").build(),
                TransitionDocument.builder("t2").from("A").on("X").when(SampleRules.map("kind", "B")).to("C").build()));
        RuleCompiler.Result r = compile(d);
        assertThat(r.problems()).isEmpty();
        assertThat(r.ok()).isTrue();
    }

    @Test
    void disjointListVersusLiteralIsNotAmbiguous() {
        RuleSetDocument d = doc(List.of(
                TransitionDocument.builder("t1").from("A").on("X").when(SampleRules.map("kind", List.of("A", "B"))).to("B").build(),
                TransitionDocument.builder("t2").from("A").on("X").when(SampleRules.map("kind", "C")).to("C").build()));
        RuleCompiler.Result r = compile(d);
        assertThat(r.problems()).isEmpty();
    }

    @Test
    void overlappingListVersusLiteralIsAmbiguous() {
        RuleSetDocument d = doc(List.of(
                TransitionDocument.builder("t1").from("A").on("X").when(SampleRules.map("kind", "A")).to("B").build(),
                TransitionDocument.builder("t2").from("A").on("X").when(SampleRules.map("kind", List.of("A", "C"))).to("C").build()));
        RuleCompiler.Result r = compile(d);
        assertThat(r.ok()).isFalse();
        assertThat(r.problems()).anySatisfy(p -> assertThat(p.message()).containsIgnoringCase("ambiguous"));
    }

    @Test
    void exactFromBeatsWildcardSoNoAmbiguity() {
        RuleSetDocument d = doc(List.of(
                TransitionDocument.builder("t1").from("A").on("X").to("B").build(),
                TransitionDocument.builder("t2").from("*").on("X").to("C").build()));
        RuleCompiler.Result r = compile(d);
        assertThat(r.problems()).isEmpty();
    }

    @Test
    void deeperPrefixBeatsShallowerSoNoAmbiguityBetweenPrefixAndExact() {
        RuleSetDocument d = doc(List.of(
                TransitionDocument.builder("t1").from("X.*").on("X").to("B").build(),
                TransitionDocument.builder("t2").from("X.Y").on("X").to("C").build()));
        RuleCompiler.Result r = compile(d);
        assertThat(r.problems()).isEmpty();
    }

    @Test
    void differingRolesDoNotDisambiguate() {
        RuleSetDocument d = doc(List.of(
                TransitionDocument.builder("t1").from("A").on("X").roles("customer").to("B").build(),
                TransitionDocument.builder("t2").from("A").on("X").roles("support").to("C").build()));
        RuleCompiler.Result r = compile(d);
        assertThat(r.ok()).isFalse();
        assertThat(r.problems()).anySatisfy(p -> assertThat(p.message()).containsIgnoringCase("ambiguous"));
    }

    @Test
    void differingGuardsDoNotDisambiguate() {
        GuardRegistry guards = GuardRegistry.of(new NamedGuard("g1"), new NamedGuard("g2"));
        RuleSetDocument d = doc(List.of(
                TransitionDocument.builder("t1").from("A").on("X").guard("g1").to("B").build(),
                TransitionDocument.builder("t2").from("A").on("X").guard("g2").to("C").build()));
        RuleCompiler.Result r = compile(d, guards);
        assertThat(r.ok()).isFalse();
        assertThat(r.problems()).anySatisfy(p -> assertThat(p.message()).containsIgnoringCase("ambiguous"));
    }

    @Test
    void unknownGuardListsKnownGuardsInTheProblem() {
        GuardRegistry guards = GuardRegistry.of(new NamedGuard("known-guard"));
        RuleSetDocument d = doc(List.of(
                TransitionDocument.builder("t1").from("A").on("X").guard("bogus-guard").to("B").build()));
        RuleCompiler.Result r = compile(d, guards);
        assertThat(r.ok()).isFalse();
        assertThat(r.problems()).anySatisfy(p -> {
            assertThat(p.message()).contains("bogus-guard");
            assertThat(p.message()).contains("known-guard");
        });
    }

    @Test
    void afterOnANotificationIsAProblem() {
        EmitDocument badNotification = new EmitDocument("N", null, null, Duration.ofHours(1), null, null);
        RuleSetDocument d = doc(List.of(
                TransitionDocument.builder("t1").from("A").on("X").to("B").emit(badNotification).build()));
        RuleCompiler.Result r = compile(d);
        assertThat(r.ok()).isFalse();
        assertThat(r.problems()).anySatisfy(p -> assertThat(p.message()).contains("after is only meaningful for a signal"));
    }

    @Test
    void dispatchOverrideWithoutReasonIsAProblem() {
        // self target defaults to INLINE; overriding to TRANSPORT without a reason must fail (DD-09)
        EmitDocument noReasonOverride = new EmitDocument("ESCALATE", TargetDocument.toSelf(), null, null,
                com.github.ifrugal.lifecycle.api.rules.Dispatch.TRANSPORT, null);
        RuleSetDocument d = doc(List.of(
                TransitionDocument.builder("t1").from("A").on("X").to("B").emit(noReasonOverride).build()));
        RuleCompiler.Result r = compile(d);
        assertThat(r.ok()).isFalse();
        assertThat(r.problems()).anySatisfy(p -> assertThat(p.message()).contains("dispatch override requires a reason"));
    }

    @Test
    void notificationWithInlineDispatchIsAProblem() {
        EmitDocument inlineNotification = new EmitDocument("N", null, null, null,
                com.github.ifrugal.lifecycle.api.rules.Dispatch.INLINE, "why not");
        RuleSetDocument d = doc(List.of(
                TransitionDocument.builder("t1").from("A").on("X").to("B").emit(inlineNotification).build()));
        RuleCompiler.Result r = compile(d);
        assertThat(r.ok()).isFalse();
        assertThat(r.problems()).anySatisfy(p -> assertThat(p.message()).contains("a notification always goes over the transport"));
    }

    @Test
    void signalToUnknownEntityTypeIsAProblem() {
        EmitDocument toUnknown = EmitDocument.signal("PING", TargetDocument.of("no-such-type", "$entity.id"), null);
        RuleSetDocument d = doc(List.of(
                TransitionDocument.builder("t1").from("A").on("X").to("B").emit(toUnknown).build()));
        RuleCompiler.Result r = compile(d);
        assertThat(r.ok()).isFalse();
        assertThat(r.problems()).anySatisfy(p -> assertThat(p.message()).contains("no-such-type").contains("has no machine"));
    }

    @Test
    void unknownDollarReferenceInPayloadIsAProblem() {
        EmitDocument badRef = EmitDocument.notification("N", SampleRules.map("x", "$totally.unknown"));
        RuleSetDocument d = doc(List.of(
                TransitionDocument.builder("t1").from("A").on("X").to("B").emit(badRef).build()));
        RuleCompiler.Result r = compile(d);
        assertThat(r.ok()).isFalse();
        assertThat(r.problems()).anySatisfy(p -> assertThat(p.message()).contains("unknown references"));
    }

    @Test
    void missingInitialIsAProblem() {
        RuleSetDocument d = new RuleSetDocument(null, "widget", null, null, List.of("A", "B"),
                List.of(TransitionDocument.builder("t1").from("A").on("X").to("B").build()));
        RuleCompiler.Result r = compile(d);
        assertThat(r.ok()).isFalse();
        assertThat(r.problems()).anySatisfy(p -> assertThat(p.message()).contains("initial is required"));
    }

    @Test
    void unreachableStateIsAWarningNotAProblem() {
        // "C" is declared but no transition ever enters it, and it is not the initial state
        RuleSetDocument d = doc(List.of(
                TransitionDocument.builder("t1").from("A").on("X").to("B").build()));
        RuleCompiler.Result r = compile(d);
        assertThat(r.ok()).isTrue();
        assertThat(r.problems()).isEmpty();
        assertThat(r.warnings()).anySatisfy(w -> assertThat(w).contains("C").contains("never entered"));
    }
}
