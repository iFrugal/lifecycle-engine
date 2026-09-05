package com.github.ifrugal.lifecycle.core.acceptance;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.AuditOutcome;
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
import com.github.ifrugal.lifecycle.core.testdomain.RefundWindowOpen;
import com.github.ifrugal.lifecycle.core.testdomain.SampleRules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance: "a new entity type without engine change" (brief §9, DD-12). Adding a machine is a data change;
 * nothing in core need be touched or recompiled.
 */
class NewEntityTypeTest {

    @Test
    void orderAloneFailsToCompileBecausePayTargetsAnUnknownType() {
        var registry = new DefinitionRegistry(new InMemoryDefinitionSource(SampleRules.order()), GuardRegistry.of(new RefundWindowOpen()), new InMemoryStateStore());
        DefinitionRegistry.ReloadResult result = registry.reload();
        assertThat(result.applied()).isFalse();
        assertThat(result.problems()).extracting(Problem::message)
                .anySatisfy(m -> assertThat(m).contains("shipment").contains("has no machine"));
    }

    @Test
    void aBrandNewEntityTypeCanBeAddedAtRuntimeWithoutAnyEngineChange() {
        try (var transport = new InMemoryTransport()) {
            var store = new InMemoryStateStore();
            var guards = GuardRegistry.of(new RefundWindowOpen());
            var source = new InMemoryDefinitionSource(SampleRules.all());
            var registry = new DefinitionRegistry(source, guards, store);
            registry.reloadOrThrow();
            var engine = new DefaultLifecycleEngine(registry, store, transport, guards);

            RuleSetDocument invoice = new RuleSetDocument(null, "invoice", "OPEN", null,
                    List.of("OPEN", "CLOSED"),
                    List.of(TransitionDocument.builder("invoice.close").from("OPEN").on("CLOSE").to("CLOSED").build()));

            List<RuleSetDocument> withInvoice = new ArrayList<>(SampleRules.all());
            withInvoice.add(invoice);
            source.set(withInvoice);

            DefinitionRegistry.ReloadResult result = registry.reload();
            assertThat(result.applied()).isTrue();

            Outcome outcome = engine.handle(LifecycleEvent.builder()
                    .entity(EntityRef.of("invoice", "inv-1")).action("CLOSE").actor(Actor.of("u1"))
                    .build());
            assertThat(outcome).isInstanceOf(Outcome.Applied.class);
            assertThat(((Outcome.Applied) outcome).to()).isEqualTo("CLOSED");
        }
    }

    @Test
    void eventForAnEntityTypeWithNoMachineIsRefusedNoMachineAndAudited() {
        try (var transport = new InMemoryTransport()) {
            var store = new InMemoryStateStore();
            var guards = GuardRegistry.of(new RefundWindowOpen());
            var registry = new DefinitionRegistry(new InMemoryDefinitionSource(SampleRules.all()), guards, store);
            registry.reloadOrThrow();
            var engine = new DefaultLifecycleEngine(registry, store, transport, guards);

            EntityRef ghost = EntityRef.of("no-such-type", "g-1");
            Outcome outcome = engine.handle(LifecycleEvent.builder()
                    .entity(ghost).action("ANYTHING").actor(Actor.of("u1"))
                    .build());

            assertThat(outcome).isInstanceOf(Outcome.Refused.class);
            assertThat(((Outcome.Refused) outcome).reason()).isEqualTo(RefusalReason.NO_MACHINE);

            assertThat(store.byEntity(ghost)).hasSize(1);
            assertThat(store.byEntity(ghost).get(0).outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(store.byEntity(ghost).get(0).reason()).isEqualTo(RefusalReason.NO_MACHINE);
        }
    }
}
