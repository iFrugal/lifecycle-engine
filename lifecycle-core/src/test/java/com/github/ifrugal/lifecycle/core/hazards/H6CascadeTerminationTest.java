package com.github.ifrugal.lifecycle.core.hazards;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.AuditOutcome;
import com.github.ifrugal.lifecycle.api.model.AuditRecord;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.Outcome;
import com.github.ifrugal.lifecycle.api.model.RefusalReason;
import com.github.ifrugal.lifecycle.api.rules.Dispatch;
import com.github.ifrugal.lifecycle.api.rules.EmitDocument;
import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;
import com.github.ifrugal.lifecycle.api.rules.TargetDocument;
import com.github.ifrugal.lifecycle.api.rules.TransitionDocument;
import com.github.ifrugal.lifecycle.core.engine.DefaultLifecycleEngine;
import com.github.ifrugal.lifecycle.core.engine.Dispatcher;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryStateStore;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryTransport;
import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import com.github.ifrugal.lifecycle.core.registry.InMemoryDefinitionSource;
import com.github.ifrugal.lifecycle.core.rules.GuardRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H6: an unbounded cascade must terminate on both paths. A three-state ring whose every edge re-emits its own
 * trigger to itself is the worst case. In process the engine sees the revisit and refuses with CYCLE before the
 * hop limit; across the transport it cannot see it, so the hop count on the envelope stops the ring at
 * {@code maxHops}.
 */
class H6CascadeTerminationTest {

    private static final int MAX_HOPS = 5;
    private static final String ACTION = "NEXT";

    /** A -> B -> C -> A, every edge re-emitting NEXT to itself. */
    private static RuleSetDocument ring(String entityType, Dispatch dispatch) {
        return new RuleSetDocument(null, entityType, "A", MAX_HOPS,
                List.of("A", "B", "C"),
                List.of(
                        edge("ring.ab", "A", "B", dispatch),
                        edge("ring.bc", "B", "C", dispatch),
                        edge("ring.ca", "C", "A", dispatch)));
    }

    private static TransitionDocument edge(String id, String from, String to, Dispatch dispatch) {
        EmitDocument emit = dispatch == null
                ? EmitDocument.signal(ACTION, TargetDocument.toSelf(), null) // default for a self signal is inline (DD-09)
                : new EmitDocument(ACTION, TargetDocument.toSelf(), null, null, dispatch, "test: force transport");
        return TransitionDocument.builder(id).from(from).on(ACTION).to(to).emit(emit).build();
    }

    private static long count(List<AuditRecord> audit, AuditOutcome outcome) {
        return audit.stream().filter(a -> a.outcome() == outcome).count();
    }

    /**
     * DD-09's dispatch table: a timer ({@code after:}) is "a signal with deliverAt" and is dispatched over the
     * transport, always — a self-targeted timer included. Otherwise every timer fires synchronously inside the
     * commit that scheduled it, which is a cascade with no delay and no way to stop it.
     */
    @Test
    void aSelfTargetedTimerIsDispatchedOverTheTransportNotInline() {
        String type = "timer";
        var store = new InMemoryStateStore();
        var guards = GuardRegistry.empty();
        RuleSetDocument rules = new RuleSetDocument(null, type, "A", null,
                List.of("A", "B"),
                List.of(
                        TransitionDocument.builder("timer.start").from("A").on("START").to("B")
                                // a timer: a self signal with `after` and no explicit dispatch override
                                .emit(new EmitDocument("REMIND", TargetDocument.toSelf(), null, Duration.ofHours(72), null, null))
                                .build(),
                        TransitionDocument.builder("timer.remind").from("B").on("REMIND").to("B").build()));
        var registry = new DefinitionRegistry(new InMemoryDefinitionSource(rules), guards, store);
        registry.reloadOrThrow();

        try (var transport = new InMemoryTransport()) {
            var published = new java.util.concurrent.CopyOnWriteArrayList<LifecycleEvent>();
            transport.subscribe(published::add);
            var engine = new DefaultLifecycleEngine(registry, store, transport, guards);
            EntityRef entity = new EntityRef(null, type, "x-1");

            Outcome outcome = engine.handle(LifecycleEvent.builder()
                    .entity(entity).action("START").actor(Actor.of("u1")).build());

            assertThat(outcome).isInstanceOfSatisfying(Outcome.Applied.class, a -> {
                assertThat(a.to()).isEqualTo("B");
                assertThat(a.emitted()).hasSize(1);
                assertThat(a.emitted().get(0).deliverAt()).as("a timer carries deliverAt").isNotNull();
            });

            // The timer must NOT have been applied inline: the entity moved once, not twice.
            assertThat(store.find(entity).orElseThrow().version())
                    .as("a 72-hour timer must not fire inside the commit that scheduled it")
                    .isEqualTo(1L);
            assertThat(count(store.allAudit(), AuditOutcome.APPLIED)).isEqualTo(1);
        }
    }

    @Test
    void inlineCascadeStopsOnTheFirstRevisitWithCycle() {
        String type = "ring-inline";
        var store = new InMemoryStateStore();
        var guards = GuardRegistry.empty();
        var registry = new DefinitionRegistry(new InMemoryDefinitionSource(ring(type, null)), guards, store);
        registry.reloadOrThrow();

        try (var transport = new InMemoryTransport()) {
            transport.subscribe(e -> { /* nothing should ever reach the transport on the inline path */ });
            var engine = new DefaultLifecycleEngine(registry, store, transport, guards);
            EntityRef entity = new EntityRef(null, type, "r-1");

            Outcome outcome = engine.handle(LifecycleEvent.builder()
                    .entity(entity).action(ACTION).actor(Actor.of("u1")).build());

            // handle() returned: the chain terminated in process.
            assertThat(outcome).isInstanceOfSatisfying(Outcome.Applied.class, a -> {
                assertThat(a.from()).isEqualTo("A");
                assertThat(a.to()).isEqualTo("B");
            });

            List<AuditRecord> audit = store.allAudit();
            assertThat(audit).as("the whole cascade fits in a handful of rows").hasSizeLessThan(10);

            assertThat(audit).anySatisfy(a -> {
                assertThat(a.outcome()).isEqualTo(AuditOutcome.REFUSED);
                assertThat(a.reason()).isEqualTo(RefusalReason.CYCLE);
                assertThat(a.detail()).isNotBlank();
                assertThat(a.entity()).isEqualTo(entity);
                assertThat(a.action()).isEqualTo(ACTION);
            });
            assertThat(count(audit, AuditOutcome.REFUSED)).isEqualTo(1);
            assertThat(count(audit, AuditOutcome.APPLIED)).isEqualTo(1);

            // The state advanced, but only a handful of times, and well short of the hop limit.
            long version = store.find(entity).orElseThrow().version();
            assertThat(version).isPositive().isLessThanOrEqualTo(MAX_HOPS);
            assertThat(count(audit, AuditOutcome.APPLIED)).isEqualTo(version);

            // Nothing escaped: an inline cascade never touches the transport.
            assertThat(transport.notifications()).isEmpty();
            assertThat(transport.deadLetters()).isEmpty();
            assertThat(transport.awaitIdle(Duration.ofSeconds(5))).isTrue();
        }
    }

    @Test
    void transportCascadeStopsAtTheHopLimit() {
        String type = "ring-transport";
        var store = new InMemoryStateStore();
        var guards = GuardRegistry.empty();
        var registry = new DefinitionRegistry(new InMemoryDefinitionSource(ring(type, Dispatch.TRANSPORT)), guards, store);
        registry.reloadOrThrow();

        try (var transport = new InMemoryTransport()) {
            var engine = new DefaultLifecycleEngine(registry, store, transport, guards);
            new Dispatcher(engine, 3).attachTo(transport); // subscribe before publishing

            EntityRef entity = new EntityRef(null, type, "r-2");
            LifecycleEvent root = LifecycleEvent.builder()
                    .entity(entity).action(ACTION).actor(Actor.of("u1")).build();

            Outcome outcome = engine.handle(root);
            assertThat(outcome).isInstanceOf(Outcome.Applied.class);

            assertThat(transport.awaitIdle(Duration.ofSeconds(5))).as("the cascade drained").isTrue();
            assertThat(transport.deadLetters()).isEmpty();

            List<AuditRecord> audit = store.allAudit();

            // Hops 0..maxHops are applied; hop maxHops+1 is refused wherever it is consumed.
            assertThat(count(audit, AuditOutcome.APPLIED))
                    .as("applied rows == maxHops + 1")
                    .isEqualTo(MAX_HOPS + 1L);

            assertThat(audit).anySatisfy(a -> {
                assertThat(a.outcome()).isEqualTo(AuditOutcome.REFUSED);
                assertThat(a.reason()).isEqualTo(RefusalReason.HOP_LIMIT);
                assertThat(a.detail()).isNotBlank();
                assertThat(a.causation().hop()).isEqualTo(MAX_HOPS + 1);
            });
            assertThat(count(audit, AuditOutcome.REFUSED)).isEqualTo(1);
            assertThat(count(audit, AuditOutcome.CONFLICTED)).isZero();

            // No CYCLE: across a broker the engine cannot see the revisit, which is exactly why hop exists.
            assertThat(audit).noneSatisfy(a -> assertThat(a.reason()).isEqualTo(RefusalReason.CYCLE));

            // The whole chain shares one correlation id and the hops are 0..maxHops+1 with no gaps.
            assertThat(store.byCorrelation(root.causation().correlationId())).hasSize(MAX_HOPS + 2);
            assertThat(audit).extracting(a -> a.causation().hop())
                    .containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5, 6);

            assertThat(store.find(entity).orElseThrow().version()).isEqualTo(MAX_HOPS + 1L);
        }
    }
}
