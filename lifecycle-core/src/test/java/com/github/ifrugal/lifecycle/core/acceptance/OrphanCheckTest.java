package com.github.ifrugal.lifecycle.core.acceptance;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.Outcome;
import com.github.ifrugal.lifecycle.api.rules.Problem;
import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;
import com.github.ifrugal.lifecycle.api.rules.TransitionDocument;
import com.github.ifrugal.lifecycle.core.engine.DefaultLifecycleEngine;
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
 * DD-06: reload validates structurally and orphan-checks removed states against the store. A reload that
 * would orphan live entities fails and keeps the previous snapshot; without a store it only warns.
 */
class OrphanCheckTest {

    /** order() with the PAID state, and every transition that mentions it, removed. */
    private static RuleSetDocument orderWithoutPaid() {
        return new RuleSetDocument(null, SampleRules.ORDER, "NEW", 16,
                List.of("NEW", "FULFILLING", "COMPLETED", "CANCELLED", "REFUND_PENDING", "REFUNDED"),
                List.of(
                        TransitionDocument.builder("order.cancel")
                                .from("*").except("COMPLETED", "REFUNDED", "CANCELLED").on("CANCEL").roles("customer", "support")
                                .to("CANCELLED").build(),
                        TransitionDocument.builder("order.complete")
                                .from("FULFILLING").on("COMPLETE").roles(SampleRules.ENGINE_ROLE).to("COMPLETED").build(),
                        TransitionDocument.builder("order.request-refund")
                                .from("COMPLETED").on("REQUEST_REFUND").roles("customer").guard(RefundWindowOpen.NAME)
                                .to("REFUND_PENDING").build(),
                        TransitionDocument.builder("order.refund-approved")
                                .from("REFUND_PENDING").on("REFUND_DECIDED").roles("finance", "lifecycle-tasks")
                                .when(SampleRules.map("decision", "APPROVED")).to("REFUNDED").build(),
                        TransitionDocument.builder("order.refund-rejected")
                                .from("REFUND_PENDING").on("REFUND_DECIDED").roles("finance", "lifecycle-tasks")
                                .when(SampleRules.map("decision", "REJECTED")).to("COMPLETED").build()));
    }

    private static Outcome payToPaid(DefaultLifecycleEngine engine, EntityRef order) {
        return engine.handle(LifecycleEvent.builder()
                .entity(order).action("PAY").actor(Actor.of("u1", "customer"))
                .payload(Map.of("payment", Map.of("status", "AUTHORISED"), "shipmentId", "s-1"))
                .build());
    }

    @Test
    void reloadRemovingAnOccupiedStateFailsAndKeepsThePreviousSnapshotLive() {
        try (var transport = new InMemoryTransport()) {
            var store = new InMemoryStateStore();
            var guards = GuardRegistry.of(new RefundWindowOpen());
            var source = new InMemoryDefinitionSource(SampleRules.order(), SampleRules.shipment());
            var registry = new DefinitionRegistry(source, guards, store);
            registry.reloadOrThrow();
            var engine = new DefaultLifecycleEngine(registry, store, transport, guards);

            EntityRef order = EntityRef.of("order", "o-orphan");
            assertThat(payToPaid(engine, order)).isInstanceOf(Outcome.Applied.class);
            assertThat(store.find(order).map(r -> r.state())).contains("PAID");

            String versionBefore = registry.snapshot().version();

            source.set(List.of(orderWithoutPaid()));
            DefinitionRegistry.ReloadResult result = registry.reload();

            assertThat(result.applied()).isFalse();
            assertThat(result.problems()).extracting(Problem::message)
                    .anySatisfy(m -> assertThat(m).contains("PAID").contains("1"));

            assertThat(registry.snapshot().version()).isEqualTo(versionBefore);

            // the engine still works against the old (still live) snapshot
            Outcome cancel = engine.handle(LifecycleEvent.builder()
                    .entity(EntityRef.of("order", "o-still-works")).action("CANCEL").actor(Actor.of("u2", "customer"))
                    .build());
            assertThat(cancel).isInstanceOf(Outcome.Applied.class);
        }
    }

    @Test
    void withoutAStoreTheSameReloadAppliesWithAWarningInstead() {
        var store = new InMemoryStateStore(); // used only by the engine, not the registry
        var guards = GuardRegistry.of(new RefundWindowOpen());
        var source = new InMemoryDefinitionSource(SampleRules.order(), SampleRules.shipment());
        var registry = new DefinitionRegistry(source, guards); // 2-arg ctor: no orphan store

        registry.reloadOrThrow();
        try (var transport = new InMemoryTransport()) {
            var engine = new DefaultLifecycleEngine(registry, store, transport, guards);
            EntityRef order = EntityRef.of("order", "o-orphan-nostore");
            assertThat(payToPaid(engine, order)).isInstanceOf(Outcome.Applied.class);
        }

        source.set(List.of(orderWithoutPaid()));
        DefinitionRegistry.ReloadResult result = registry.reload();

        assertThat(result.applied()).isTrue();
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("PAID").contains("no store available"));
    }

    @Test
    void reloadWhereNothingSitsInTheRemovedStateApplies() {
        var store = new InMemoryStateStore();
        var guards = GuardRegistry.of(new RefundWindowOpen());
        var source = new InMemoryDefinitionSource(SampleRules.order(), SampleRules.shipment());
        var registry = new DefinitionRegistry(source, guards, store);
        registry.reloadOrThrow();

        // no entity was ever driven to PAID
        source.set(List.of(orderWithoutPaid()));
        DefinitionRegistry.ReloadResult result = registry.reload();

        assertThat(result.applied()).isTrue();
        assertThat(result.problems()).isEmpty();
    }
}
