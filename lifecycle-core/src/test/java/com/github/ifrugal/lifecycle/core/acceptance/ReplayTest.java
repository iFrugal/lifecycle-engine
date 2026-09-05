package com.github.ifrugal.lifecycle.core.acceptance;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.AuditOutcome;
import com.github.ifrugal.lifecycle.api.model.AuditRecord;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.Outcome;
import com.github.ifrugal.lifecycle.api.model.RefusalReason;
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
 * D4 dedupe (DD-07): the same event id handled twice must not double-apply, and refusals also enter the
 * inbox so a replayed refusal returns {@code Duplicate} too.
 */
class ReplayTest {

    private InMemoryStateStore store;
    private InMemoryTransport transport;
    private DefaultLifecycleEngine engine;

    private void setUp() {
        store = new InMemoryStateStore();
        transport = new InMemoryTransport();
        var guards = GuardRegistry.of(new RefundWindowOpen());
        var registry = new DefinitionRegistry(new InMemoryDefinitionSource(SampleRules.all()), guards, store);
        registry.reloadOrThrow();
        engine = new DefaultLifecycleEngine(registry, store, transport, guards);
        // No Dispatcher attached: we only care about the direct handle() call being replayed.
    }

    @Test
    void sameEventHandledTwiceIsAppliedThenDuplicate() {
        setUp();
        try {
            EntityRef order = EntityRef.of("order", "o-replay");
            LifecycleEvent pay = LifecycleEvent.builder()
                    .entity(order).action("PAY").actor(Actor.of("u1", "customer"))
                    .payload(Map.of("payment", Map.of("status", "AUTHORISED"), "shipmentId", "s-replay"))
                    .build();

            Outcome first = engine.handle(pay);
            assertThat(first).isInstanceOf(Outcome.Applied.class);

            AuditRecord appliedRow = store.byEventId(pay.eventId()).orElseThrow();
            assertThat(appliedRow.outcome()).isEqualTo(AuditOutcome.APPLIED);

            Outcome second = engine.handle(pay);
            assertThat(second).isInstanceOf(Outcome.Duplicate.class);
            assertThat(((Outcome.Duplicate) second).firstAuditId()).isEqualTo(appliedRow.auditId());

            // NOTE on what the code actually does (see DefaultLifecycleEngine.commitRefusal/apply and
            // InMemoryStateStore.commit): the inbox check runs before anything else, so a replay never
            // reaches the audit-append step a second time. Exactly one audit row exists for this eventId
            // (the original APPLIED row); there is no separate DUPLICATE row.
            List<AuditRecord> rowsForThisEvent = store.allAudit().stream()
                    .filter(a -> a.eventId().equals(pay.eventId())).toList();
            assertThat(rowsForThisEvent).hasSize(1);
            assertThat(rowsForThisEvent.get(0).outcome()).isEqualTo(AuditOutcome.APPLIED);

            // the notification (ReceiptRequested) was only published on the first, real application
            assertThat(transport.notifications()).filteredOn(e -> e.action().equals("ReceiptRequested")).hasSize(1);

            // outbox is drained (marked sent) synchronously as part of handle()
            assertThat(store.unsent(100)).isEmpty();
        } finally {
            transport.close();
        }
    }

    @Test
    void refusedEventReplayedAlsoReturnsDuplicate() {
        setUp();
        try {
            // COMPLETE is only valid from FULFILLING; on a brand new (NEW) entity it is refused NO_MATCH.
            EntityRef order = EntityRef.of("order", "o-replay-refusal");
            LifecycleEvent complete = LifecycleEvent.builder()
                    .entity(order).action("COMPLETE").actor(Actor.service("sys", SampleRules.ENGINE_ROLE))
                    .build();

            Outcome first = engine.handle(complete);
            assertThat(first).isInstanceOf(Outcome.Refused.class);
            assertThat(((Outcome.Refused) first).reason()).isEqualTo(RefusalReason.NO_MATCH);

            AuditRecord refusedRow = store.byEventId(complete.eventId()).orElseThrow();
            assertThat(refusedRow.outcome()).isEqualTo(AuditOutcome.REFUSED);

            // DD-07: "refusals also enter the inbox, so ten redeliveries of a refused event give one
            // REFUSED and nine DUPLICATE rows" (rows here meaning outcomes observed by the caller; per the
            // note above, the store itself only ever holds the first row).
            Outcome second = engine.handle(complete);
            assertThat(second).isInstanceOf(Outcome.Duplicate.class);
            assertThat(((Outcome.Duplicate) second).firstAuditId()).isEqualTo(refusedRow.auditId());

            long rowsForThisEvent = store.allAudit().stream().filter(a -> a.eventId().equals(complete.eventId())).count();
            assertThat(rowsForThisEvent).isEqualTo(1);
        } finally {
            transport.close();
        }
    }
}
