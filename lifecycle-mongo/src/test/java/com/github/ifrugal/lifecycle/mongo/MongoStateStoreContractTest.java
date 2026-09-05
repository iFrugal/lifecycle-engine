package com.github.ifrugal.lifecycle.mongo;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.AuditOutcome;
import com.github.ifrugal.lifecycle.api.model.AuditRecord;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.RefusalReason;
import com.github.ifrugal.lifecycle.api.model.StateRecord;
import com.github.ifrugal.lifecycle.api.spi.Commit;
import com.github.ifrugal.lifecycle.api.spi.CommitResult;
import com.github.ifrugal.lifecycle.api.spi.Outbox;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;

import static com.github.ifrugal.lifecycle.mongo.TestSupport.actor;
import static com.github.ifrugal.lifecycle.mongo.TestSupport.applied;
import static com.github.ifrugal.lifecycle.mongo.TestSupport.conflicted;
import static com.github.ifrugal.lifecycle.mongo.TestSupport.id;
import static com.github.ifrugal.lifecycle.mongo.TestSupport.refused;
import static org.assertj.core.api.Assertions.assertThat;

/** Store contract, transactional mode (DD-07, DD-11): {@link MongoStateStore} constructed with {@code useTransactions = true}. */
@Testcontainers(disabledWithoutDocker = true)
class MongoStateStoreContractTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

    private MongoClient client;
    private MongoDatabase database;
    private MongoStateStore store;

    @BeforeEach
    void setUp() {
        client = MongoClients.create(MONGO.getConnectionString());
        database = client.getDatabase("test_" + id().replace("-", ""));
        MongoSchema.ensureIndexes(database);
        store = new MongoStateStore(client, database, Duration.ofDays(7), true);
    }

    @AfterEach
    void tearDown() {
        database.drop();
        client.close();
    }

    @Test
    void findOnAbsentEntityIsEmpty() {
        assertThat(store.find(EntityRef.of("widget", "never-seen"))).isEmpty();
    }

    @Test
    void advancingCommitAppliesAndIsFindable() {
        EntityRef ref = EntityRef.of("widget", "w1");
        String eventId = id();
        AuditRecord audit = applied(id(), eventId, ref, "A", "B", "t1");

        CommitResult result = store.commit(new Commit(ref, 0L, "B", eventId, audit, List.of()));

        assertThat(result).isInstanceOf(CommitResult.Committed.class);
        assertThat(((CommitResult.Committed) result).version()).isEqualTo(1L);
        assertThat(((CommitResult.Committed) result).auditId()).isEqualTo(audit.auditId());

        StateRecord found = store.find(ref).orElseThrow();
        assertThat(found.state()).isEqualTo("B");
        assertThat(found.version()).isEqualTo(1L);
        assertThat(found.lastEventId()).isEqualTo(eventId);
    }

    @Test
    void refusalCommitDoesNotBumpVersionButAddsAuditAndInboxEntry() {
        EntityRef ref = EntityRef.of("widget", "w-refusal");
        String eventId = id();
        AuditRecord audit = refused(id(), eventId, ref, null, RefusalReason.NO_MATCH, "no transition for this action");

        CommitResult result = store.commit(new Commit(ref, 0L, null, eventId, audit, List.of()));

        assertThat(result).isInstanceOf(CommitResult.Committed.class);
        assertThat(((CommitResult.Committed) result).version()).isEqualTo(0L);
        // no state change: an entity that never advanced stays absent from find(), same as InMemoryStateStore.
        assertThat(store.find(ref)).isEmpty();

        AuditRecord stored = store.byEventId(eventId).orElseThrow();
        assertThat(stored.outcome()).isEqualTo(AuditOutcome.REFUSED);
        assertThat(stored.reason()).isEqualTo(RefusalReason.NO_MATCH);

        // the inbox entry is proven by dedupe: replaying the same event id is recognised, not re-applied.
        AuditRecord replayAudit = refused(id(), eventId, ref, null, RefusalReason.NO_MATCH, "no transition for this action");
        CommitResult replay = store.commit(new Commit(ref, 0L, null, eventId, replayAudit, List.of()));
        assertThat(replay).isInstanceOf(CommitResult.AlreadyApplied.class);
        assertThat(((CommitResult.AlreadyApplied) replay).firstAuditId()).isEqualTo(audit.auditId());
    }

    @Test
    void duplicateEventIdReturnsAlreadyAppliedWithTheFirstAuditId() {
        EntityRef ref = EntityRef.of("widget", "w-dup");
        String eventId = id();
        String firstAuditId = id();
        CommitResult first = store.commit(new Commit(ref, 0L, "B", eventId, applied(firstAuditId, eventId, ref, "A", "B", "t1"), List.of()));
        assertThat(first).isInstanceOf(CommitResult.Committed.class);

        // Redelivered with a different audit id and a (now stale) expectedVersion: dedupe wins regardless (DD-07 order).
        CommitResult second = store.commit(new Commit(ref, 0L, "B", eventId, applied(id(), eventId, ref, "A", "B", "t1"), List.of()));
        assertThat(second).isInstanceOf(CommitResult.AlreadyApplied.class);
        assertThat(((CommitResult.AlreadyApplied) second).firstAuditId()).isEqualTo(firstAuditId);

        assertThat(store.find(ref).orElseThrow().version()).isEqualTo(1L);
    }

    @Test
    void staleExpectedVersionReturnsVersionMismatchAndWritesNothing() {
        EntityRef ref = EntityRef.of("widget", "w-stale");
        store.commit(new Commit(ref, 0L, "B", id(), applied(id(), id(), ref, "A", "B", "t1"), List.of()));
        assertThat(store.find(ref).orElseThrow().version()).isEqualTo(1L);

        String staleEventId = id();
        CommitResult mismatch = store.commit(new Commit(ref, 0L, "C", staleEventId, applied(id(), staleEventId, ref, "A", "C", "t2"), List.of()));
        assertThat(mismatch).isInstanceOf(CommitResult.VersionMismatch.class);
        assertThat(((CommitResult.VersionMismatch) mismatch).expected()).isEqualTo(0L);
        assertThat(((CommitResult.VersionMismatch) mismatch).actual()).isEqualTo(1L);

        // atomicity: the state is unchanged and nothing was written for the aborted attempt...
        assertThat(store.find(ref).orElseThrow().version()).isEqualTo(1L);
        assertThat(store.find(ref).orElseThrow().state()).isEqualTo("B");
        assertThat(store.byEventId(staleEventId)).isEmpty();

        // ...proven decisively: the same event id can still be committed fresh at the correct version.
        CommitResult retry = store.commit(new Commit(ref, 1L, "C", staleEventId, applied(id(), staleEventId, ref, "B", "C", "t2"), List.of()));
        assertThat(retry).isInstanceOf(CommitResult.Committed.class);
    }

    @Test
    void outboxUnsentThenMarkSent() {
        EntityRef ref = EntityRef.of("widget", "w-outbox");
        LifecycleEvent emitted = LifecycleEvent.builder().entity(ref).action("NOTIFY").actor(actor("engine")).build();
        String eventId = id();
        store.commit(new Commit(ref, 0L, "B", eventId, applied(id(), eventId, ref, "A", "B", "t1"), List.of(emitted)));

        Outbox outbox = store.outbox().orElseThrow();
        List<LifecycleEvent> unsent = outbox.unsent(10);
        assertThat(unsent).extracting(LifecycleEvent::eventId).contains(emitted.eventId());

        outbox.markSent(List.of(emitted.eventId()));
        assertThat(outbox.unsent(10)).extracting(LifecycleEvent::eventId).doesNotContain(emitted.eventId());
    }

    @Test
    void countInStateCountsAcrossOrWithinATenant() {
        commitNew(new EntityRef("acme", "widget", "a1"), "READY");
        commitNew(new EntityRef("acme", "widget", "a2"), "READY");
        commitNew(new EntityRef("globex", "widget", "g1"), "READY");
        commitNew(new EntityRef(null, "widget", "n1"), "READY");

        assertThat(store.countInState(null, "widget", "READY")).hasValue(4L);
        assertThat(store.countInState("acme", "widget", "READY")).hasValue(2L);
        assertThat(store.countInState("globex", "widget", "READY")).hasValue(1L);
        assertThat(store.countInState("acme", "widget", "OTHER")).hasValue(0L);
    }

    private void commitNew(EntityRef ref, String toState) {
        String eventId = id();
        CommitResult r = store.commit(new Commit(ref, 0L, toState, eventId, applied(id(), eventId, ref, "A", toState, "t1"), List.of()));
        assertThat(r).isInstanceOf(CommitResult.Committed.class);
    }

    @Test
    void appendDetachedAddsAnAuditRowNotTiedToAVersion() {
        EntityRef ref = EntityRef.of("widget", "w-detached");
        AuditRecord row = conflicted(id(), id(), ref, "A", "expected version 3 but the entity is at 1");

        store.appendDetached(row);

        AuditRecord stored = store.byEventId(row.eventId()).orElseThrow();
        assertThat(stored.outcome()).isEqualTo(AuditOutcome.CONFLICTED);
        assertThat(stored.auditId()).isEqualTo(row.auditId());
        assertThat(stored.detail()).isEqualTo(row.detail());
        assertThat(store.find(ref)).isEmpty();
    }

    @Test
    void auditQueriesByEntityByEventIdAndByCorrelation() {
        EntityRef ref = EntityRef.of("widget", "w-audit");
        String e1 = id();
        String e2 = id();
        store.commit(new Commit(ref, 0L, "B", e1, applied(id(), e1, ref, "A", "B", "t1"), List.of()));
        store.commit(new Commit(ref, 1L, "C", e2, applied(id(), e2, ref, "B", "C", "t2"), List.of()));

        assertThat(store.byEntity(ref)).extracting(AuditRecord::eventId).containsExactlyInAnyOrder(e1, e2);
        assertThat(store.byEventId(e1)).isPresent();
        assertThat(store.byEventId("does-not-exist")).isEmpty();

        // Causation.root(eventId) makes the event its own correlation id.
        assertThat(store.byCorrelation(e1)).extracting(AuditRecord::eventId).containsExactly(e1);
    }

    @Test
    void tenantEmptyStringAndNullRoundTripToTheSameRecord() {
        EntityRef refEmpty = new EntityRef("", "widget", "w-tenant");
        assertThat(refEmpty.tenantId()).as("EntityRef normalises blank to null").isNull();

        String eventId = id();
        store.commit(new Commit(refEmpty, 0L, "B", eventId, applied(id(), eventId, refEmpty, "A", "B", "t1"), List.of()));

        EntityRef refNull = new EntityRef(null, "widget", "w-tenant");
        assertThat(refNull.key()).isEqualTo(refEmpty.key());

        StateRecord found = store.find(refNull).orElseThrow();
        assertThat(found.ref().tenantId()).isNull();
        assertThat(found.state()).isEqualTo("B");
    }
}
