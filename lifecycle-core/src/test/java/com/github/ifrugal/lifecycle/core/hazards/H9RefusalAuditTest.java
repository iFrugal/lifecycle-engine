package com.github.ifrugal.lifecycle.core.hazards;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.AuditOutcome;
import com.github.ifrugal.lifecycle.api.model.AuditRecord;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.Outcome;
import com.github.ifrugal.lifecycle.api.model.RefusalReason;
import com.github.ifrugal.lifecycle.core.engine.DefaultLifecycleEngine;
import com.github.ifrugal.lifecycle.core.engine.EngineConfig;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryStateStore;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryTransport;
import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import com.github.ifrugal.lifecycle.core.registry.InMemoryDefinitionSource;
import com.github.ifrugal.lifecycle.core.rules.GuardRegistry;
import com.github.ifrugal.lifecycle.core.testdomain.RefundWindowOpen;
import com.github.ifrugal.lifecycle.core.testdomain.SampleRules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H9: nothing is dropped silently. Every refusal is a sealed {@link Outcome.Refused} AND a committed audit row
 * carrying the reason, a human detail, the state the entity was in and the rule set version the decision was
 * made under — so "why was this refused?" is answerable months later. A refusal never bumps the version.
 */
class H9RefusalAuditTest {

    private static final EntityRef ORDER = new EntityRef(null, SampleRules.ORDER, "o-1");
    private static final Actor CUSTOMER = Actor.of("u1", "customer");
    private static final Actor ENGINE = Actor.service("sys", EngineConfig.ENGINE_ACTOR_ID);

    private InMemoryStateStore store;
    private DefinitionRegistry registry;
    private InMemoryTransport transport;
    private DefaultLifecycleEngine engine;

    @BeforeEach
    void setUp() {
        store = new InMemoryStateStore();
        var guards = GuardRegistry.of(new RefundWindowOpen());
        registry = new DefinitionRegistry(new InMemoryDefinitionSource(SampleRules.all()), guards, store);
        registry.reloadOrThrow();
        transport = new InMemoryTransport();
        transport.subscribe(e -> { /* drain cross-entity signals; this test only reads the audit */ });
        engine = new DefaultLifecycleEngine(registry, store, transport, guards);
    }

    @AfterEach
    void tearDown() {
        transport.close();
    }

    private LifecycleEvent event(EntityRef ref, String action, Actor actor, Map<String, Object> payload) {
        return LifecycleEvent.builder().entity(ref).action(action).actor(actor).payload(payload).build();
    }

    /** Refused with this reason, and the audit row says the same thing. */
    private AuditRecord assertRefused(LifecycleEvent e, RefusalReason reason, String expectedFromState) {
        Outcome outcome = engine.handle(e);

        assertThat(outcome).isInstanceOfSatisfying(Outcome.Refused.class, r -> {
            assertThat(r.reason()).isEqualTo(reason);
            assertThat(r.detail()).as("the outcome carries a human explanation").isNotBlank();
        });

        Optional<AuditRecord> row = store.byEventId(e.eventId());
        assertThat(row).as("every refusal is committed to the audit (H9)").isPresent();

        AuditRecord a = row.orElseThrow();
        assertThat(a.outcome()).isEqualTo(AuditOutcome.REFUSED);
        assertThat(a.reason()).isEqualTo(reason);
        assertThat(a.detail()).as("audit detail for %s", reason).isNotBlank();
        assertThat(a.detail()).isEqualTo(((Outcome.Refused) outcome).detail());
        assertThat(a.fromState()).as("state the entity was in when it was refused").isEqualTo(expectedFromState);
        assertThat(a.toState()).isNull();
        assertThat(a.transitionId()).isNull();
        assertThat(a.ruleSetVersion()).as("the snapshot the decision was made under").isEqualTo(registry.snapshot().version());
        assertThat(a.eventId()).isEqualTo(e.eventId());
        assertThat(a.entity()).isEqualTo(e.entity());
        assertThat(a.action()).isEqualTo(e.action());
        assertThat(a.actor()).isEqualTo(e.actor());
        assertThat(a.at()).isNotNull();
        assertThat(a.auditId()).isNotBlank();
        assertThat(a.causation()).isNotNull();
        return a;
    }

    @Test
    void unknownActionIsNoMatch() {
        assertRefused(event(ORDER, "TELEPORT", CUSTOMER, null), RefusalReason.NO_MATCH, "NEW");
    }

    @Test
    void anActionThatExistsButNotFromThisStateIsNoMatch() {
        assertRefused(event(ORDER, "COMPLETE", ENGINE, null), RefusalReason.NO_MATCH, "NEW");
    }

    @Test
    void wrongRoleIsRoleDenied() {
        AuditRecord a = assertRefused(
                event(ORDER, "PAY", Actor.of("u1", "warehouse"),
                        SampleRules.map("payment", SampleRules.map("status", "AUTHORISED"), "shipmentId", "s-9")),
                RefusalReason.ROLE_DENIED, "NEW");
        assertThat(a.detail()).contains("order.pay");
    }

    @Test
    void failingWhenIsGuardFailed() {
        AuditRecord a = assertRefused(
                event(ORDER, "PAY", CUSTOMER,
                        SampleRules.map("payment", SampleRules.map("status", "DECLINED"), "shipmentId", "s-9")),
                RefusalReason.GUARD_FAILED, "NEW");
        assertThat(a.detail()).contains("order.pay");
    }

    @Test
    void aNamedGuardReturningFalseIsGuardFailed() {
        driveToCompleted();
        assertThat(store.find(ORDER).orElseThrow().state()).isEqualTo("COMPLETED");

        AuditRecord a = assertRefused(
                event(ORDER, "REQUEST_REFUND", CUSTOMER, SampleRules.map("refundWindowClosed", true, "reason", "late")),
                RefusalReason.GUARD_FAILED, "COMPLETED");
        assertThat(a.detail()).contains("order.request-refund");

        // The same event with the window open is applied, so the refusal was the guard and nothing else.
        assertThat(engine.handle(event(ORDER, "REQUEST_REFUND", CUSTOMER, SampleRules.map("reason", "late"))))
                .isInstanceOf(Outcome.Applied.class);
    }

    @Test
    void unknownEntityTypeIsNoMachineWithNoFromState() {
        AuditRecord a = assertRefused(
                event(new EntityRef(null, "unicorn", "u-1"), "PAY", CUSTOMER, null),
                RefusalReason.NO_MACHINE, null);
        assertThat(a.detail()).contains("unicorn");
        assertThat(store.find(new EntityRef(null, "unicorn", "u-1"))).isEmpty();
    }

    @Test
    void aTenantWithoutAnOverlayStillFallsBackToTheBaseMachine() {
        // globex has no overlay: NO_MACHINE must not be reported just because the tenant is unknown.
        EntityRef globex = new EntityRef("globex", SampleRules.ORDER, "o-7");
        assertRefused(event(globex, "TELEPORT", CUSTOMER, null), RefusalReason.NO_MATCH, "NEW");
    }

    @Test
    void refusalsDoNotBumpTheVersion() {
        assertThat(engine.handle(event(ORDER, "PAY", CUSTOMER,
                SampleRules.map("payment", SampleRules.map("status", "AUTHORISED", "amount", 120), "shipmentId", "s-9"))))
                .isInstanceOf(Outcome.Applied.class);

        long versionBefore = store.find(ORDER).orElseThrow().version();
        String stateBefore = store.find(ORDER).orElseThrow().state();
        assertThat(versionBefore).isEqualTo(1L);

        assertRefused(event(ORDER, "TELEPORT", CUSTOMER, null), RefusalReason.NO_MATCH, stateBefore);
        assertRefused(event(ORDER, "PAY", CUSTOMER,
                SampleRules.map("payment", SampleRules.map("status", "AUTHORISED"), "shipmentId", "s-9")),
                RefusalReason.NO_MATCH, stateBefore);
        assertRefused(event(ORDER, "COMPLETE", Actor.of("u1", "nobody"), null), RefusalReason.NO_MATCH, stateBefore);

        assertThat(store.find(ORDER).orElseThrow().version()).as("a refusal never bumps the version").isEqualTo(versionBefore);
        assertThat(store.find(ORDER).orElseThrow().state()).isEqualTo(stateBefore);
        assertThat(store.byEntity(ORDER).stream().filter(a -> a.outcome() == AuditOutcome.REFUSED)).hasSize(3);
    }

    @Test
    void everyRefusalReasonReachableFromRulesIsAuditedWithItsOwnRow() {
        assertRefused(event(new EntityRef(null, "unicorn", "u-2"), "PAY", CUSTOMER, null), RefusalReason.NO_MACHINE, null);
        assertRefused(event(ORDER, "TELEPORT", CUSTOMER, null), RefusalReason.NO_MATCH, "NEW");
        assertRefused(event(ORDER, "PAY", Actor.of("u1", "warehouse"),
                SampleRules.map("payment", SampleRules.map("status", "AUTHORISED"), "shipmentId", "s-9")),
                RefusalReason.ROLE_DENIED, "NEW");
        assertRefused(event(ORDER, "PAY", CUSTOMER,
                SampleRules.map("payment", SampleRules.map("status", "DECLINED"), "shipmentId", "s-9")),
                RefusalReason.GUARD_FAILED, "NEW");

        assertThat(store.allAudit())
                .allSatisfy(a -> assertThat(a.outcome()).isEqualTo(AuditOutcome.REFUSED))
                .extracting(AuditRecord::reason)
                .containsExactly(RefusalReason.NO_MACHINE, RefusalReason.NO_MATCH, RefusalReason.ROLE_DENIED, RefusalReason.GUARD_FAILED);

        assertThat(store.allAudit()).extracting(AuditRecord::auditId).doesNotHaveDuplicates();
        assertThat(store.allStates()).as("nothing moved").isEmpty();
    }

    private void driveToCompleted() {
        assertThat(engine.handle(event(ORDER, "PAY", CUSTOMER,
                SampleRules.map("payment", SampleRules.map("status", "AUTHORISED", "amount", 120), "shipmentId", "s-9"))))
                .isInstanceOf(Outcome.Applied.class);
        assertThat(engine.handle(event(ORDER, "SHIPMENT_PREPARED", ENGINE, null))).isInstanceOf(Outcome.Applied.class);
        assertThat(engine.handle(event(ORDER, "COMPLETE", ENGINE, null))).isInstanceOf(Outcome.Applied.class);
    }
}
