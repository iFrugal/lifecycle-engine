package com.github.ifrugal.lifecycle.core.acceptance;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.Outcome;
import com.github.ifrugal.lifecycle.api.model.RefusalReason;
import com.github.ifrugal.lifecycle.api.rules.Problem;
import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;
import com.github.ifrugal.lifecycle.api.rules.TransitionDocument;
import com.github.ifrugal.lifecycle.core.engine.DefaultLifecycleEngine;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryStateStore;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryTransport;
import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import com.github.ifrugal.lifecycle.core.registry.InMemoryDefinitionSource;
import com.github.ifrugal.lifecycle.core.rules.GuardRegistry;
import com.github.ifrugal.lifecycle.core.rules.OverlayMerger;
import com.github.ifrugal.lifecycle.core.testdomain.RefundWindowOpen;
import com.github.ifrugal.lifecycle.core.testdomain.SampleRules;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DD-05 tenant overlays: replace-whole / disable / add, states additive, and fallback to the base machine for
 * tenants without an overlay (the acceptance brief's "rule change is a reviewed data change").
 */
class TenantOverlayTest {

    private DefaultLifecycleEngine newEngine(InMemoryStateStore store, InMemoryTransport transport) {
        var guards = GuardRegistry.of(new RefundWindowOpen());
        var source = new InMemoryDefinitionSource(SampleRules.all());
        var registry = new DefinitionRegistry(source, guards, store);
        registry.reloadOrThrow();
        // No Dispatcher attached: cross-entity emissions are published but never delivered, so driving an
        // order through PAY does not race with an asynchronous PREPARE/SHIPMENT_PREPARED cascade. Every
        // state transition in this test is applied by calling engine.handle(...) directly and synchronously.
        return new DefaultLifecycleEngine(registry, store, transport, guards);
    }

    private Outcome pay(DefaultLifecycleEngine engine, EntityRef order, String shipmentId) {
        return engine.handle(LifecycleEvent.builder()
                .entity(order).action("PAY").actor(Actor.of("cust", "customer"))
                .payload(Map.of("payment", Map.of("status", "AUTHORISED"), "shipmentId", shipmentId))
                .build());
    }

    private Outcome engineSignal(DefaultLifecycleEngine engine, EntityRef order, String action) {
        return engine.handle(LifecycleEvent.builder()
                .entity(order).action(action).actor(Actor.service("sys", SampleRules.ENGINE_ROLE))
                .build());
    }

    @Test
    void acmeCancelIsDisabledSoNewEntityRefusesWithNoMatch() {
        try (var transport = new InMemoryTransport()) {
            var store = new InMemoryStateStore();
            var engine = newEngine(store, transport);
            EntityRef order = new EntityRef("acme", "order", "o-cancel");

            Outcome outcome = engine.handle(LifecycleEvent.builder()
                    .entity(order).action("CANCEL").actor(Actor.of("c1", "customer"))
                    .build());

            assertThat(outcome).isInstanceOf(Outcome.Refused.class);
            assertThat(((Outcome.Refused) outcome).reason()).isEqualTo(RefusalReason.NO_MATCH);
        }
    }

    @Test
    void acmeHoldFromPaidByStoreManagerIsApplied() {
        try (var transport = new InMemoryTransport()) {
            var store = new InMemoryStateStore();
            var engine = newEngine(store, transport);
            EntityRef order = new EntityRef("acme", "order", "o-hold");

            assertThat(pay(engine, order, "s-hold")).isInstanceOf(Outcome.Applied.class);

            Outcome hold = engine.handle(LifecycleEvent.builder()
                    .entity(order).action("HOLD").actor(Actor.of("m1", "store-manager"))
                    .build());

            assertThat(hold).isInstanceOf(Outcome.Applied.class);
            assertThat(((Outcome.Applied) hold).to()).isEqualTo("ON_HOLD");
            assertThat(store.find(order).map(r -> r.state())).contains("ON_HOLD");
        }
    }

    @Test
    void acmeRequestRefundReplacesTheBaseEdgeWholeSoNoGuardApplies() {
        try (var transport = new InMemoryTransport()) {
            var store = new InMemoryStateStore();
            var engine = newEngine(store, transport);
            EntityRef order = new EntityRef("acme", "order", "o-refund");

            assertThat(pay(engine, order, "s-refund")).isInstanceOf(Outcome.Applied.class);
            assertThat(engineSignal(engine, order, "SHIPMENT_PREPARED")).isInstanceOf(Outcome.Applied.class);
            assertThat(engineSignal(engine, order, "COMPLETE")).isInstanceOf(Outcome.Applied.class);
            assertThat(store.find(order)).get().extracting(r -> r.state()).isEqualTo("COMPLETED");

            // the base edge names a guard (refund-window-open) and requires only "customer"; the acme
            // overlay replaces the whole transition with no guard and roles customer/store-manager, and
            // this must apply even when refundWindowClosed=true, proving the base guard is gone entirely.
            Outcome refund = engine.handle(LifecycleEvent.builder()
                    .entity(order).action("REQUEST_REFUND").actor(Actor.of("m1", "store-manager"))
                    .payload(Map.of("channel", "STORE", "refundWindowClosed", true))
                    .build());

            assertThat(refund).isInstanceOf(Outcome.Applied.class);
            assertThat(((Outcome.Applied) refund).to()).isEqualTo("REFUND_PENDING");
        }
    }

    @Test
    void globexTenantFallsBackToTheBaseMachine() {
        try (var transport = new InMemoryTransport()) {
            var store = new InMemoryStateStore();
            var engine = newEngine(store, transport);

            EntityRef cancelOrder = new EntityRef("globex", "order", "o-cancel-globex");
            Outcome cancel = engine.handle(LifecycleEvent.builder()
                    .entity(cancelOrder).action("CANCEL").actor(Actor.of("c1", "customer"))
                    .build());
            assertThat(cancel).isInstanceOf(Outcome.Applied.class);
            assertThat(((Outcome.Applied) cancel).to()).isEqualTo("CANCELLED");

            EntityRef holdOrder = new EntityRef("globex", "order", "o-hold-globex");
            Outcome hold = engine.handle(LifecycleEvent.builder()
                    .entity(holdOrder).action("HOLD").actor(Actor.of("m1", "store-manager"))
                    .build());
            assertThat(hold).isInstanceOf(Outcome.Refused.class);
            assertThat(((Outcome.Refused) hold).reason()).isEqualTo(RefusalReason.NO_MATCH);
        }
    }

    // --- OverlayMerger, tested directly ---

    private static RuleSetDocument base() {
        return new RuleSetDocument(null, "widget", "A", null, List.of("A", "B", "C"),
                List.of(
                        TransitionDocument.builder("t.a").from("A").on("X").to("B").build(),
                        TransitionDocument.builder("t.b").from("B").on("Y").to("C").build()));
    }

    @Test
    void overlayReplacesWholeDisablesAndAdds() {
        RuleSetDocument overlay = new RuleSetDocument("acme", "widget", null, null, List.of(),
                List.of(
                        TransitionDocument.builder("t.a").from("A").on("X").roles("special").to("C").build(), // replace
                        TransitionDocument.disabled("t.b"), // disable
                        TransitionDocument.builder("t.c").from("C").on("Z").to("A").build())); // add

        OverlayMerger.Result r = OverlayMerger.merge(base(), overlay);
        assertThat(r.ok()).isTrue();
        Map<String, TransitionDocument> byId = r.merged().transitions().stream()
                .collect(java.util.stream.Collectors.toMap(TransitionDocument::id, t -> t));
        assertThat(byId).containsKeys("t.a", "t.c").doesNotContainKey("t.b");
        assertThat(byId.get("t.a").to()).isEqualTo("C"); // replaced whole, not merged
        assertThat(byId.get("t.a").roles()).containsExactly("special");
    }

    @Test
    void overlayStatesAreAdditive() {
        RuleSetDocument overlay = new RuleSetDocument("acme", "widget", null, null, List.of("D"), List.of());
        OverlayMerger.Result r = OverlayMerger.merge(base(), overlay);
        assertThat(r.ok()).isTrue();
        assertThat(r.merged().states()).containsExactlyInAnyOrder("A", "B", "C", "D");
    }

    @Test
    void disablingAnUnknownTransitionIdIsAProblem() {
        RuleSetDocument overlay = new RuleSetDocument("acme", "widget", null, null, List.of(),
                List.of(TransitionDocument.disabled("no-such-id")));
        OverlayMerger.Result r = OverlayMerger.merge(base(), overlay);
        assertThat(r.ok()).isFalse();
        assertThat(r.problems()).anySatisfy(p -> assertThat(p.message()).contains("disables a transition the base does not have"));
    }

    @Test
    void registryRejectsAnOverlayWithNoBase() {
        RuleSetDocument orphanOverlay = new RuleSetDocument("acme", "no-such-type", null, null, List.of(),
                List.of(TransitionDocument.builder("t").from("A").on("X").to("B").build()));
        var registry = new DefinitionRegistry(new InMemoryDefinitionSource(List.of(orphanOverlay)), GuardRegistry.empty(), new InMemoryStateStore());
        DefinitionRegistry.ReloadResult result = registry.reload();
        assertThat(result.applied()).isFalse();
        assertThat(result.problems()).extracting(Problem::message).anySatisfy(m -> assertThat(m).contains("overlay has no base rule set"));
    }
}
