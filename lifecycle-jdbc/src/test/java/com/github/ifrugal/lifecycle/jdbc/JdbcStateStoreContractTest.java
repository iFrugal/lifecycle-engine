package com.github.ifrugal.lifecycle.jdbc;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.AuditOutcome;
import com.github.ifrugal.lifecycle.api.model.AuditRecord;
import com.github.ifrugal.lifecycle.api.model.Causation;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.EventKind;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.RefusalReason;
import com.github.ifrugal.lifecycle.api.model.StateRecord;
import com.github.ifrugal.lifecycle.api.spi.Commit;
import com.github.ifrugal.lifecycle.api.spi.CommitResult;
import com.github.ifrugal.lifecycle.api.spi.Outbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The commit contract of DD-07, asserted against a real database. Every assertion here holds for
 * {@code InMemoryStateStore} too: this is the same contract, not a JDBC-flavoured one. Subclasses supply the
 * {@link DataSource} and {@link Dialect}, so H2 and PostgreSQL run identical expectations.
 */
abstract class JdbcStateStoreContractTest {

    private static final String TYPE = "order";

    protected abstract DataSource dataSource();

    protected abstract Dialect dialect();

    protected JdbcStateStore store;
    private DataSource ds;

    @BeforeEach
    void installAndClear() {
        ds = dataSource();
        SchemaInstaller.install(ds, dialect());
        TestDatabases.truncateAll(ds);
        store = new JdbcStateStore(ds, dialect(), Duration.ofDays(7));
    }

    // ------------------------------------------------------------------ find

    @Test
    void findOfAnEntityTheStoreHasNeverSeenIsEmpty() {
        assertThat(store.find(EntityRef.of(TYPE, "never-seen"))).isEmpty();
    }

    // ------------------------------------------------------------------ commit

    @Test
    void firstAdvancingCommitCreatesTheRecordAtVersionOne() {
        EntityRef ref = EntityRef.of(TYPE, "o-1");
        AuditRecord audit = applied("a-1", "e-1", ref, "NEW", "PAID");

        CommitResult result = store.commit(new Commit(ref, 0L, "PAID", "e-1", audit, List.of()));

        assertThat(result).isEqualTo(new CommitResult.Committed(1L, "a-1"));
        StateRecord record = store.find(ref).orElseThrow();
        assertThat(record.state()).isEqualTo("PAID");
        assertThat(record.version()).isEqualTo(1L);
        assertThat(record.lastEventId()).isEqualTo("e-1");
        assertThat(record.ruleSetVersion()).isEqualTo("rs-1");
        assertThat(record.ref()).isEqualTo(ref);
        assertThat(record.updatedAt()).isNotNull();
    }

    @Test
    void secondAdvancingCommitBumpsTheVersion() {
        EntityRef ref = EntityRef.of(TYPE, "o-2");
        store.commit(new Commit(ref, 0L, "PAID", "e-1", applied("a-1", "e-1", ref, "NEW", "PAID"), List.of()));

        CommitResult second = store.commit(new Commit(ref, 1L, "FULFILLING", "e-2", applied("a-2", "e-2", ref, "PAID", "FULFILLING"), List.of()));

        assertThat(second).isEqualTo(new CommitResult.Committed(2L, "a-2"));
        assertThat(store.find(ref).orElseThrow().version()).isEqualTo(2L);
        assertThat(store.find(ref).orElseThrow().state()).isEqualTo("FULFILLING");
    }

    @Test
    void refusalIsAuditedAndDedupedWithoutTouchingTheVersion() {
        EntityRef ref = EntityRef.of(TYPE, "o-3");
        store.commit(new Commit(ref, 0L, "PAID", "e-1", applied("a-1", "e-1", ref, "NEW", "PAID"), List.of()));

        CommitResult refusal = store.commit(new Commit(ref, 1L, null, "e-2", refused("a-2", "e-2", ref, "PAID"), List.of()));

        assertThat(refusal).isEqualTo(new CommitResult.Committed(1L, "a-2"));
        assertThat(store.find(ref).orElseThrow().version()).as("a refusal does not bump the version").isEqualTo(1L);
        assertThat(store.find(ref).orElseThrow().state()).isEqualTo("PAID");
        assertThat(store.byEntity(ref)).extracting(AuditRecord::auditId).containsExactly("a-1", "a-2");
        assertThat(TestDatabases.count(ds, "lifecycle_inbox")).as("the refusal is in the inbox too (DD-07)").isEqualTo(2);
    }

    @Test
    void refusalOnAnEntityWithNoRowCommitsAtVersionZero() {
        EntityRef ref = EntityRef.of(TYPE, "o-4");

        CommitResult result = store.commit(new Commit(ref, 0L, null, "e-1", refused("a-1", "e-1", ref, "NEW"), List.of()));

        assertThat(result).isEqualTo(new CommitResult.Committed(0L, "a-1"));
        assertThat(store.find(ref)).as("a refusal never creates the state row").isEmpty();
        assertThat(store.byEntity(ref)).hasSize(1);
    }

    @Test
    void theSameEventIdAgainIsAlreadyAppliedWithTheFirstAuditId() {
        EntityRef ref = EntityRef.of(TYPE, "o-5");
        store.commit(new Commit(ref, 0L, "PAID", "e-1", applied("a-1", "e-1", ref, "NEW", "PAID"), List.of()));

        CommitResult replay = store.commit(new Commit(ref, 1L, "FULFILLING", "e-1", applied("a-99", "e-1", ref, "PAID", "FULFILLING"), List.of()));

        assertThat(replay).isEqualTo(new CommitResult.AlreadyApplied("a-1"));
        assertThat(store.find(ref).orElseThrow().version()).isEqualTo(1L);
        assertThat(store.byEntity(ref)).extracting(AuditRecord::auditId).containsExactly("a-1");
    }

    @Test
    void theInboxIsCheckedBeforeTheVersionSoAReplayIsAReplayAtAnyVersion() {
        EntityRef ref = EntityRef.of(TYPE, "o-6");
        store.commit(new Commit(ref, 0L, "PAID", "e-1", applied("a-1", "e-1", ref, "NEW", "PAID"), List.of()));

        // Stale expected version *and* a replayed event id: the inbox wins (DD-07).
        CommitResult replay = store.commit(new Commit(ref, 0L, "PAID", "e-1", applied("a-98", "e-1", ref, "NEW", "PAID"), List.of()));

        assertThat(replay).isEqualTo(new CommitResult.AlreadyApplied("a-1"));
    }

    @Test
    void staleExpectedVersionIsAVersionMismatchAndWritesNothingAtAll() {
        EntityRef ref = EntityRef.of(TYPE, "o-7");
        store.commit(new Commit(ref, 0L, "PAID", "e-1", applied("a-1", "e-1", ref, "NEW", "PAID"), List.of()));
        long auditBefore = TestDatabases.count(ds, "lifecycle_audit");
        long inboxBefore = TestDatabases.count(ds, "lifecycle_inbox");

        CommitResult stale = store.commit(new Commit(ref, 0L, "CANCELLED", "e-2",
                applied("a-2", "e-2", ref, "NEW", "CANCELLED"), List.of(event("emitted-1", ref))));

        assertThat(stale).isEqualTo(new CommitResult.VersionMismatch(0L, 1L));
        assertThat(store.find(ref).orElseThrow().state()).isEqualTo("PAID");
        assertThat(TestDatabases.count(ds, "lifecycle_audit")).as("no audit row").isEqualTo(auditBefore);
        assertThat(TestDatabases.count(ds, "lifecycle_inbox")).as("no inbox row").isEqualTo(inboxBefore);
        assertThat(TestDatabases.count(ds, "lifecycle_outbox")).as("no outbox row").isZero();
    }

    @Test
    void staleExpectedVersionOnANonAdvancingCommitAlsoWritesNothing() {
        EntityRef ref = EntityRef.of(TYPE, "o-8");
        store.commit(new Commit(ref, 0L, "PAID", "e-1", applied("a-1", "e-1", ref, "NEW", "PAID"), List.of()));

        CommitResult stale = store.commit(new Commit(ref, 7L, null, "e-2", refused("a-2", "e-2", ref, "PAID"), List.of()));

        assertThat(stale).isEqualTo(new CommitResult.VersionMismatch(7L, 1L));
        assertThat(TestDatabases.count(ds, "lifecycle_audit")).isEqualTo(1);
        assertThat(TestDatabases.count(ds, "lifecycle_inbox")).isEqualTo(1);
    }

    @Test
    void expectedVersionAheadOfAnAbsentEntityIsAMismatchAgainstZero() {
        EntityRef ref = EntityRef.of(TYPE, "o-9");

        CommitResult stale = store.commit(new Commit(ref, 3L, "PAID", "e-1", applied("a-1", "e-1", ref, "NEW", "PAID"), List.of()));

        assertThat(stale).isEqualTo(new CommitResult.VersionMismatch(3L, 0L));
        assertThat(TestDatabases.count(ds, "lifecycle_audit")).isZero();
    }

    // ------------------------------------------------------------------ outbox

    @Test
    void committedEventsAreUnsentUntilMarked() {
        EntityRef ref = EntityRef.of(TYPE, "o-10");
        LifecycleEvent one = event("emit-1", ref);
        LifecycleEvent two = event("emit-2", ref);
        store.commit(new Commit(ref, 0L, "PAID", "e-1", applied("a-1", "e-1", ref, "NEW", "PAID"), List.of(one, two)));

        Outbox outbox = store.outbox().orElseThrow();
        List<LifecycleEvent> unsent = outbox.unsent(10);
        assertThat(unsent).extracting(LifecycleEvent::eventId).containsExactlyInAnyOrder("emit-1", "emit-2");
        assertThat(unsent).allSatisfy(e -> {
            assertThat(e.entity()).isEqualTo(ref);
            assertThat(e.action()).isEqualTo("ReceiptRequested");
            assertThat(e.payload()).containsEntry("amount", 42);
        });

        outbox.markSent(List.of("emit-1"));
        assertThat(outbox.unsent(10)).extracting(LifecycleEvent::eventId).containsExactly("emit-2");

        outbox.markSent(List.of("emit-2"));
        assertThat(outbox.unsent(10)).isEmpty();
        assertThat(TestDatabases.count(ds, "lifecycle_outbox")).as("sent rows are kept").isEqualTo(2);
    }

    @Test
    void unsentHonoursTheLimit() {
        EntityRef ref = EntityRef.of(TYPE, "o-11");
        store.commit(new Commit(ref, 0L, "PAID", "e-1", applied("a-1", "e-1", ref, "NEW", "PAID"),
                List.of(event("x-1", ref), event("x-2", ref), event("x-3", ref))));

        assertThat(store.outbox().orElseThrow().unsent(2)).hasSize(2);
    }

    // ------------------------------------------------------------------ countInState

    @Test
    void countInStateIsPerTenantOrAcrossAllOfThem() {
        commitInto(new EntityRef(null, TYPE, "base-1"), "PAID");
        commitInto(new EntityRef("acme", TYPE, "acme-1"), "PAID");
        commitInto(new EntityRef("acme", TYPE, "acme-2"), "PAID");
        commitInto(new EntityRef("globex", TYPE, "globex-1"), "CANCELLED");

        assertThat(store.countInState(null, TYPE, "PAID")).hasValue(3L);
        assertThat(store.countInState("acme", TYPE, "PAID")).hasValue(2L);
        assertThat(store.countInState("globex", TYPE, "PAID")).hasValue(0L);
        assertThat(store.countInState(null, TYPE, "CANCELLED")).hasValue(1L);
        assertThat(store.countInState(null, "shipment", "PAID")).hasValue(0L);
    }

    // ------------------------------------------------------------------ audit

    @Test
    void appendDetachedAddsAnAuditRowOutsideAnyCommit() {
        EntityRef ref = EntityRef.of(TYPE, "o-12");
        AuditRecord conflict = new AuditRecord("a-c", "e-c", ref, "PAY", Actor.of("u1", "customer"), "PAID", null, null,
                AuditOutcome.CONFLICTED, null, "expected version 0 but the entity is at 1", "rs-1",
                Instant.now().truncatedTo(ChronoUnit.MILLIS), Causation.root("e-c"));

        store.appendDetached(conflict);

        List<AuditRecord> rows = store.byEntity(ref);
        assertThat(rows).hasSize(1);
        AuditRecord back = rows.getFirst();
        assertThat(back.outcome()).isEqualTo(AuditOutcome.CONFLICTED);
        assertThat(back.detail()).isEqualTo("expected version 0 but the entity is at 1");
        assertThat(back.actor()).isEqualTo(Actor.of("u1", "customer"));
        assertThat(back.at()).isEqualTo(conflict.at());
        assertThat(TestDatabases.count(ds, "lifecycle_inbox")).as("a detached row is not deduped").isZero();
    }

    @Test
    void auditIsQueryableByEntityByEventIdAndByCorrelation() {
        EntityRef order = EntityRef.of(TYPE, "o-13");
        EntityRef shipment = EntityRef.of("shipment", "s-13");
        Causation root = Causation.root("e-root");
        Causation hop1 = root.child("e-root");

        store.commit(new Commit(order, 0L, "PAID", "e-root",
                new AuditRecord("a-root", "e-root", order, "PAY", Actor.of("u1"), "NEW", "PAID", "order.pay",
                        AuditOutcome.APPLIED, null, null, "rs-1", Instant.parse("2026-01-01T10:00:00Z"), root),
                List.of()));
        store.commit(new Commit(shipment, 0L, "PREPARING", "e-child",
                new AuditRecord("a-child", "e-child", shipment, "PREPARE", Actor.of("lifecycle-engine"), "NEW", "PREPARING", "shipment.prepare",
                        AuditOutcome.APPLIED, null, null, "rs-1", Instant.parse("2026-01-01T10:00:01Z"), hop1),
                List.of()));
        store.commit(new Commit(order, 1L, null, "e-late",
                new AuditRecord("a-late", "e-late", order, "PAY", Actor.of("u1"), "PAID", null, null,
                        AuditOutcome.REFUSED, RefusalReason.NO_MATCH, "no transition from PAID on PAY", "rs-1",
                        Instant.parse("2026-01-01T10:00:02Z"), root),
                List.of()));

        assertThat(store.byEntity(order)).extracting(AuditRecord::auditId).containsExactly("a-root", "a-late");
        assertThat(store.byEntity(shipment)).extracting(AuditRecord::auditId).containsExactly("a-child");

        Optional<AuditRecord> byEvent = store.byEventId("e-child");
        assertThat(byEvent).isPresent();
        assertThat(byEvent.orElseThrow().transitionId()).isEqualTo("shipment.prepare");
        assertThat(store.byEventId("never-happened")).isEmpty();

        assertThat(store.byCorrelation("e-root"))
                .as("ordered by hop, then time")
                .extracting(AuditRecord::auditId)
                .containsExactly("a-root", "a-late", "a-child");
        assertThat(store.byCorrelation("e-root")).extracting(a -> a.causation().hop()).containsExactly(0, 0, 1);
    }

    @Test
    void refusalReasonAndDetailSurviveTheRoundTrip() {
        EntityRef ref = EntityRef.of(TYPE, "o-14");
        store.commit(new Commit(ref, 0L, null, "e-1", refused("a-1", "e-1", ref, "NEW"), List.of()));

        AuditRecord back = store.byEventId("e-1").orElseThrow();
        assertThat(back.outcome()).isEqualTo(AuditOutcome.REFUSED);
        assertThat(back.reason()).isEqualTo(RefusalReason.ROLE_DENIED);
        assertThat(back.detail()).isEqualTo("actor holds none of [customer]");
        assertThat(back.toState()).isNull();
        assertThat(back.transitionId()).isNull();
        assertThat(back.actor().roles()).containsExactly("support");
        assertThat(back.actor().kind()).isEqualTo(Actor.Kind.HUMAN);
    }

    // ------------------------------------------------------------------ inbox retention

    @Test
    void purgeExpiredInboxDeletesRowsWhoseRetentionHasElapsed() {
        JdbcStateStore zeroRetention = new JdbcStateStore(ds, dialect(), Duration.ZERO);
        EntityRef ref = EntityRef.of(TYPE, "o-15");
        zeroRetention.commit(new Commit(ref, 0L, "PAID", "e-1", applied("a-1", "e-1", ref, "NEW", "PAID"), List.of()));
        assertThat(TestDatabases.count(ds, "lifecycle_inbox")).isEqualTo(1);

        assertThat(zeroRetention.purgeExpiredInbox()).isEqualTo(1);
        assertThat(TestDatabases.count(ds, "lifecycle_inbox")).isZero();
        assertThat(store.byEntity(ref)).as("purging the inbox never touches the audit").hasSize(1);

        // With the inbox gone, the same event id is accepted again - natural idempotence is the backstop (DD-07).
        CommitResult again = zeroRetention.commit(new Commit(ref, 1L, "FULFILLING", "e-1",
                applied("a-2", "e-1", ref, "PAID", "FULFILLING"), List.of()));
        assertThat(again).isEqualTo(new CommitResult.Committed(2L, "a-2"));
    }

    @Test
    void purgeExpiredInboxLeavesLiveRowsAlone() {
        EntityRef ref = EntityRef.of(TYPE, "o-16");
        store.commit(new Commit(ref, 0L, "PAID", "e-1", applied("a-1", "e-1", ref, "NEW", "PAID"), List.of()));

        assertThat(store.purgeExpiredInbox()).isZero();
        assertThat(TestDatabases.count(ds, "lifecycle_inbox")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ tenancy

    @Test
    void anEmptyTenantIsTheAbsentTenantAndNeverCollidesWithARealOne() {
        EntityRef blank = new EntityRef("", TYPE, "shared-id");
        EntityRef nul = new EntityRef(null, TYPE, "shared-id");
        EntityRef acme = new EntityRef("acme", TYPE, "shared-id");
        assertThat(blank).as("the model already folds a blank tenant to null").isEqualTo(nul);

        store.commit(new Commit(nul, 0L, "PAID", "e-base", applied("a-base", "e-base", nul, "NEW", "PAID"), List.of()));
        store.commit(new Commit(acme, 0L, "CANCELLED", "e-acme", applied("a-acme", "e-acme", acme, "NEW", "CANCELLED"), List.of()));

        StateRecord base = store.find(blank).orElseThrow();
        assertThat(base.ref().tenantId()).as("'' comes back as null, not as an empty string").isNull();
        assertThat(base.state()).isEqualTo("PAID");
        assertThat(store.find(acme).orElseThrow().state()).isEqualTo("CANCELLED");
        assertThat(store.find(acme).orElseThrow().ref().tenantId()).isEqualTo("acme");

        assertThat(store.byEntity(nul)).extracting(AuditRecord::auditId).containsExactly("a-base");
        assertThat(store.byEntity(acme)).extracting(AuditRecord::auditId).containsExactly("a-acme");
        assertThat(store.byEntity(nul)).allSatisfy(a -> assertThat(a.entity().tenantId()).isNull());

        // The same event id on the same id under a different tenant is a different inbox key.
        assertThat(store.commit(new Commit(acme, 1L, "REFUNDED", "e-base", applied("a-x", "e-base", acme, "CANCELLED", "REFUNDED"), List.of())))
                .isEqualTo(new CommitResult.Committed(2L, "a-x"));
    }

    // ------------------------------------------------------------------ helpers

    private void commitInto(EntityRef ref, String state) {
        String eventId = "e-" + ref.key() + "-" + state;
        store.commit(new Commit(ref, 0L, state, eventId, applied("a-" + eventId, eventId, ref, "NEW", state), List.of()));
    }

    private static AuditRecord applied(String auditId, String eventId, EntityRef ref, String from, String to) {
        return new AuditRecord(auditId, eventId, ref, "PAY", Actor.of("u1", "customer"), from, to, "order.pay",
                AuditOutcome.APPLIED, null, null, "rs-1", Instant.now().truncatedTo(ChronoUnit.MILLIS), Causation.root(eventId));
    }

    private static AuditRecord refused(String auditId, String eventId, EntityRef ref, String from) {
        return new AuditRecord(auditId, eventId, ref, "PAY", Actor.of("u2", "support"), from, null, null,
                AuditOutcome.REFUSED, RefusalReason.ROLE_DENIED, "actor holds none of [customer]", "rs-1",
                Instant.now().truncatedTo(ChronoUnit.MILLIS), Causation.root(eventId));
    }

    private static LifecycleEvent event(String eventId, EntityRef ref) {
        return LifecycleEvent.builder()
                .eventId(eventId)
                .kind(EventKind.NOTIFICATION)
                .entity(ref)
                .action("ReceiptRequested")
                .actor(Actor.service("lifecycle-engine"))
                .payload(java.util.Map.of("amount", 42))
                .occurredAt(Instant.parse("2026-01-01T09:00:00Z"))
                .build();
    }
}
