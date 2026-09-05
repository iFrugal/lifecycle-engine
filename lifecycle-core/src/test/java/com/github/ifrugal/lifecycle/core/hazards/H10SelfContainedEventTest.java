package com.github.ifrugal.lifecycle.core.hazards;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.EventKind;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.Outcome;
import com.github.ifrugal.lifecycle.core.engine.DefaultLifecycleEngine;
import com.github.ifrugal.lifecycle.core.engine.EngineConfig;
import com.github.ifrugal.lifecycle.core.engine.TransitionResolver;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryStateStore;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryTransport;
import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import com.github.ifrugal.lifecycle.core.registry.InMemoryDefinitionSource;
import com.github.ifrugal.lifecycle.core.rules.GuardRegistry;
import com.github.ifrugal.lifecycle.core.testdomain.RefundWindowOpen;
import com.github.ifrugal.lifecycle.core.testdomain.SampleRules;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H10: leaky effects. An emitted event is assembled from the consumed event alone, through {@code $}-projections
 * and nothing else. The proof is that a second, completely fresh engine — its own registry, store and transport,
 * sharing no object with the first — can consume the emitted signal and move its entity. Only the event crosses.
 */
class H10SelfContainedEventTest {

    private record Rig(DefinitionRegistry registry, InMemoryStateStore store, InMemoryTransport transport,
                       DefaultLifecycleEngine engine) implements AutoCloseable {
        @Override
        public void close() {
            transport.close();
        }
    }

    /** A whole engine built from nothing but the sample rule documents. */
    private static Rig freshRig() {
        var store = new InMemoryStateStore();
        var guards = GuardRegistry.of(new RefundWindowOpen());
        var registry = new DefinitionRegistry(new InMemoryDefinitionSource(SampleRules.all()), guards, store);
        registry.reloadOrThrow();
        var transport = new InMemoryTransport();
        transport.subscribe(e -> { /* park emitted signals; this test hands them across by hand */ });
        return new Rig(registry, store, transport, new DefaultLifecycleEngine(registry, store, transport, guards));
    }

    private static LifecycleEvent pay() {
        return LifecycleEvent.builder()
                .entity(new EntityRef(null, SampleRules.ORDER, "o-1"))
                .action("PAY")
                .actor(Actor.of("u1", "customer"))
                .payload(SampleRules.map(
                        "payment", SampleRules.map("status", "AUTHORISED", "amount", 120),
                        "shipmentId", "s-9"))
                .build();
    }

    @Test
    void emittedEventsCarryEverythingTheyNeedAndAreConsumedByAFreshEngine() {
        LifecycleEvent parent = pay();
        LifecycleEvent prepare;
        LifecycleEvent receipt;

        try (Rig first = freshRig()) {
            Outcome outcome = first.engine().handle(parent);

            assertThat(outcome).isInstanceOf(Outcome.Applied.class);
            List<LifecycleEvent> emitted = ((Outcome.Applied) outcome).emitted();
            assertThat(emitted).hasSize(2);

            receipt = emitted.stream().filter(e -> e.action().equals("ReceiptRequested")).findFirst().orElseThrow();
            prepare = emitted.stream().filter(e -> e.action().equals("PREPARE")).findFirst().orElseThrow();

            // --- the notification: payload projected from the consumed event, and nothing else ---
            assertThat(receipt.kind()).isEqualTo(EventKind.NOTIFICATION);
            assertThat(receipt.entity()).isEqualTo(parent.entity());
            assertThat(receipt.payload()).isEqualTo(Map.of("orderId", "o-1", "amount", 120));
            assertThat(receipt.eventId()).isEqualTo(TransitionResolver.emittedId(parent.eventId(), emitted.indexOf(receipt)));
            assertThat(receipt.eventId()).isEqualTo(TransitionResolver.emittedId(parent.eventId(), 0));

            // --- the cross-entity signal: target id resolved from the consumed payload ---
            assertThat(prepare.kind()).isEqualTo(EventKind.SIGNAL);
            assertThat(prepare.entity()).isEqualTo(new EntityRef(null, SampleRules.SHIPMENT, "s-9"));
            assertThat(prepare.payload()).isEqualTo(Map.of("orderId", "o-1"));
            assertThat(prepare.eventId()).isEqualTo(TransitionResolver.emittedId(parent.eventId(), 1));
            assertThat(prepare.deliverAt()).isNull();
            assertThat(prepare.expectedVersion()).isNull();

            // --- both are stamped by the engine and chained to their parent ---
            for (LifecycleEvent e : List.of(receipt, prepare)) {
                assertThat(e.actor().id()).isEqualTo(EngineConfig.ENGINE_ACTOR_ID);
                assertThat(e.actor().roles()).contains(EngineConfig.ENGINE_ACTOR_ID);
                assertThat(e.actor().kind()).isEqualTo(Actor.Kind.ENGINE);
                assertThat(e.causation().correlationId()).isEqualTo(parent.causation().correlationId());
                assertThat(e.causation().causationId()).isEqualTo(parent.eventId());
                assertThat(e.causation().hop()).isEqualTo(1);
            }

            assertThat(first.store().find(parent.entity()).orElseThrow().state()).isEqualTo("PAID");
        }

        // The first engine, its registry, its store and its transport are all gone. Only `prepare` survived.
        try (Rig second = freshRig()) {
            EntityRef shipment = new EntityRef(null, SampleRules.SHIPMENT, "s-9");
            assertThat(second.store().find(shipment)).as("the second engine has never seen this shipment").isEmpty();
            assertThat(second.store().allAudit()).isEmpty();

            Outcome outcome = second.engine().handle(prepare);

            assertThat(outcome).isInstanceOfSatisfying(Outcome.Applied.class, a -> {
                assertThat(a.transitionId()).isEqualTo("shipment.prepare");
                assertThat(a.from()).isEqualTo("PENDING");
                assertThat(a.to()).isEqualTo("PREPARED");
                assertThat(a.version()).isEqualTo(1L);
                // and it in turn emits a self-contained signal back toward the order
                assertThat(a.emitted()).hasSize(1);
                LifecycleEvent back = a.emitted().get(0);
                assertThat(back.action()).isEqualTo("SHIPMENT_PREPARED");
                assertThat(back.entity()).isEqualTo(new EntityRef(null, SampleRules.ORDER, "o-1"));
                assertThat(back.payload()).isEqualTo(Map.of("shipmentId", "s-9"));
                assertThat(back.causation().correlationId()).isEqualTo(prepare.causation().correlationId());
                assertThat(back.causation().causationId()).isEqualTo(prepare.eventId());
                assertThat(back.causation().hop()).isEqualTo(2);
            });

            assertThat(second.store().find(shipment).orElseThrow().state()).isEqualTo("PREPARED");
        }
    }

    @Test
    void emittedIdsAreDeterministicSoAReplayedParentReEmitsIdenticalChildren() {
        LifecycleEvent parent = pay();

        List<String> firstRun;
        List<String> secondRun;

        try (Rig a = freshRig()) {
            firstRun = ((Outcome.Applied) a.engine().handle(parent)).emitted().stream().map(LifecycleEvent::eventId).toList();
        }
        try (Rig b = freshRig()) {
            secondRun = ((Outcome.Applied) b.engine().handle(parent)).emitted().stream().map(LifecycleEvent::eventId).toList();
        }

        assertThat(firstRun).isEqualTo(secondRun);
        assertThat(firstRun).containsExactly(
                TransitionResolver.emittedId(parent.eventId(), 0),
                TransitionResolver.emittedId(parent.eventId(), 1));
        assertThat(firstRun).doesNotHaveDuplicates();
    }

    @Test
    void aSignalWhoseTargetIdCannotBeProjectedIsRefusedNotSilentlyRetargeted() {
        try (Rig rig = freshRig()) {
            // No shipmentId in the payload: the target id `$payload.shipmentId` resolves to nothing.
            Outcome outcome = rig.engine().handle(LifecycleEvent.builder()
                    .entity(new EntityRef(null, SampleRules.ORDER, "o-2"))
                    .action("PAY")
                    .actor(Actor.of("u1", "customer"))
                    .payload(SampleRules.map("payment", SampleRules.map("status", "AUTHORISED", "amount", 120)))
                    .build());

            assertThat(outcome).isInstanceOf(Outcome.Refused.class);
            assertThat(rig.store().find(new EntityRef(null, SampleRules.ORDER, "o-2"))).isEmpty();
        }
    }
}
