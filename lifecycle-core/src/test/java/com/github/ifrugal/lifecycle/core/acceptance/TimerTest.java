package com.github.ifrugal.lifecycle.core.acceptance;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.AuditOutcome;
import com.github.ifrugal.lifecycle.api.model.Decision;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.EventKind;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.Outcome;
import com.github.ifrugal.lifecycle.api.rules.Dispatch;
import com.github.ifrugal.lifecycle.api.rules.EmitDocument;
import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;
import com.github.ifrugal.lifecycle.api.rules.TargetDocument;
import com.github.ifrugal.lifecycle.api.rules.TransitionDocument;
import com.github.ifrugal.lifecycle.api.spi.Transport;
import com.github.ifrugal.lifecycle.core.engine.DefaultLifecycleEngine;
import com.github.ifrugal.lifecycle.core.engine.Dispatcher;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryStateStore;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryTransport;
import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import com.github.ifrugal.lifecycle.core.registry.InMemoryDefinitionSource;
import com.github.ifrugal.lifecycle.core.rules.GuardRegistry;
import com.github.ifrugal.lifecycle.core.testdomain.RefundWindowOpen;
import com.github.ifrugal.lifecycle.core.testdomain.SampleRules;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DD-09: a timer (`after:`) is a signal with {@code deliverAt}, and per the table in DD-09 must always go over the transport. */
class TimerTest {

    /**
     * BUG (see final report): {@code RuleCompiler.compileEmit} picks the default dispatch purely from
     * {@code to().self()} ({@code self -> INLINE}, otherwise {@code TRANSPORT}); it never looks at
     * {@code after}. DD-09's table says a timer's default dispatch is always {@code transport}, regardless
     * of target. {@code order.request-refund}'s {@code ESCALATE} emission targets {@code self} with
     * {@code after: 72h}, so it compiles to {@code Dispatch.INLINE} — meaning, per
     * {@code DefaultLifecycleEngine.dispatch}, the "timer" is actually applied synchronously and
     * immediately (ignoring {@code deliverAt}) rather than being handed to the transport to deliver after
     * the delay. Fix: {@code compileEmit} should force {@code Dispatch.TRANSPORT} whenever {@code after != null}.
     */
    @Test
    void selfTargetedTimerEmissionDispatchesOverTransportPerDD09() {
        Decision.Match match = decideRequestRefund();
        com.github.ifrugal.lifecycle.api.model.Emission emission = match.emissions().stream()
                .filter(e -> e.event().action().equals("ESCALATE")).findFirst().orElseThrow();
        assertThat(emission.dispatch()).isEqualTo(Dispatch.TRANSPORT);
    }

    @Test
    void timerEmissionCarriesTheCorrectDeliverAtAndIsAKindSignal() {
        Decision.Match match = decideRequestRefund();
        com.github.ifrugal.lifecycle.api.model.Emission emission = match.emissions().stream()
                .filter(e -> e.event().action().equals("ESCALATE")).findFirst().orElseThrow();
        LifecycleEvent emitted = emission.event();
        assertThat(emitted.kind()).isEqualTo(EventKind.SIGNAL);
        assertThat(emitted.deliverAt()).isEqualTo(emitted.occurredAt().plus(Duration.ofHours(72)));
    }

    /** Decides order.request-refund from COMPLETED, returning the Match so its ESCALATE emission can be inspected. */
    private Decision.Match decideRequestRefund() {
        var store = new InMemoryStateStore();
        var guards = GuardRegistry.of(new RefundWindowOpen());
        var registry = new DefinitionRegistry(new InMemoryDefinitionSource(SampleRules.all()), guards, store);
        registry.reloadOrThrow();
        try (var transport = new InMemoryTransport()) {
            var engine = new DefaultLifecycleEngine(registry, store, transport, guards);
            EntityRef order = EntityRef.of("order", "o-timer");
            engine.handle(pay(order));
            engine.handle(engineSignal(order, "SHIPMENT_PREPARED"));
            engine.handle(engineSignal(order, "COMPLETE"));

            LifecycleEvent requestRefund = LifecycleEvent.builder()
                    .entity(order).action("REQUEST_REFUND").actor(Actor.of("u1", "customer"))
                    .payload(java.util.Map.of("reason", "changed mind"))
                    .build();
            Decision decision = engine.evaluate(requestRefund);
            assertThat(decision).isInstanceOf(Decision.Match.class);
            return (Decision.Match) decision;
        }
    }

    private static LifecycleEvent pay(EntityRef order) {
        return LifecycleEvent.builder().entity(order).action("PAY").actor(Actor.of("u1", "customer"))
                .payload(java.util.Map.of("payment", java.util.Map.of("status", "AUTHORISED"), "shipmentId", "s-timer"))
                .build();
    }

    private static LifecycleEvent engineSignal(EntityRef order, String action) {
        return LifecycleEvent.builder().entity(order).action(action).actor(Actor.service("sys", SampleRules.ENGINE_ROLE)).build();
    }

    // --- fast, real timer delivery over the transport ---

    private static RuleSetDocument tinyTimerMachine(Dispatch dispatch, String reason) {
        EmitDocument tick = new EmitDocument("TICK", TargetDocument.toSelf(), null, Duration.ofMillis(50), dispatch, reason);
        return new RuleSetDocument(null, "timerdemo", "A", null, List.of("A", "B"),
                List.of(
                        TransitionDocument.builder("start").from("A").on("START").to("A").emit(tick).build(),
                        TransitionDocument.builder("tick").from("A").on("TICK").to("B").build()));
    }

    @Test
    void aFastTimerFiresOverTheTransportAndTheFollowOnTransitionIsAudited() {
        var store = new InMemoryStateStore();
        var guards = GuardRegistry.empty();
        // dispatch explicitly forced to TRANSPORT (with a reason) to exercise real timer delivery,
        // independent of the default-dispatch bug documented above.
        var source = new InMemoryDefinitionSource(tinyTimerMachine(Dispatch.TRANSPORT, "timers must go over the transport (DD-09)"));
        var registry = new DefinitionRegistry(source, guards, store);
        registry.reloadOrThrow();

        try (var transport = new InMemoryTransport()) {
            var engine = new DefaultLifecycleEngine(registry, store, transport, guards);
            new Dispatcher(engine, 3).attachTo(transport);

            EntityRef entity = EntityRef.of("timerdemo", "t-1");
            Outcome outcome = engine.handle(LifecycleEvent.builder().entity(entity).action("START").actor(Actor.of("u1")).build());
            assertThat(outcome).isInstanceOf(Outcome.Applied.class);

            boolean idle = transport.awaitIdle(Duration.ofSeconds(5));
            assertThat(idle).isTrue();

            assertThat(store.find(entity).map(r -> r.state())).contains("B");
            assertThat(store.byEntity(entity)).anySatisfy(a -> {
                assertThat(a.action()).isEqualTo("TICK");
                assertThat(a.transitionId()).isEqualTo("tick");
                assertThat(a.outcome()).isEqualTo(AuditOutcome.APPLIED);
            });
        }
    }

    @Test
    void engineConstructionRefusesWhenRulesRequireDelayButTransportCannot() {
        var store = new InMemoryStateStore();
        var guards = GuardRegistry.empty();
        var source = new InMemoryDefinitionSource(tinyTimerMachine(Dispatch.TRANSPORT, "must be transport for the delay to matter"));
        var registry = new DefinitionRegistry(source, guards, store);
        registry.reloadOrThrow();
        assertThat(registry.requiresDelay()).isTrue();

        Transport noDelayTransport = new Transport() {
            @Override public void publish(LifecycleEvent event) { /* unused */ }
            @Override public void subscribe(Consumer<LifecycleEvent> inbound) { /* unused */ }
            @Override public boolean supportsDelay() { return false; }
        };

        assertThatThrownBy(() -> new DefaultLifecycleEngine(registry, store, noDelayTransport, guards))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("after");
    }
}
