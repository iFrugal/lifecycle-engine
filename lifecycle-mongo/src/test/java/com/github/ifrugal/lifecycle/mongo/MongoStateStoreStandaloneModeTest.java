package com.github.ifrugal.lifecycle.mongo;

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
import static com.github.ifrugal.lifecycle.mongo.TestSupport.id;
import static com.github.ifrugal.lifecycle.mongo.TestSupport.refused;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Same core contract as {@link MongoStateStoreContractTest}, but against the standalone (no replica set)
 * fallback described in {@link MongoStateStore}'s class Javadoc: {@code useTransactions = false}, so the
 * state document itself — with an embedded inbox and pending outbox — is the sole unit of atomicity.
 */
@Testcontainers(disabledWithoutDocker = true)
class MongoStateStoreStandaloneModeTest {

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
        store = new MongoStateStore(client, database, Duration.ofDays(7), false);
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
        CommitResult result = store.commit(new Commit(ref, 0L, "B", eventId, applied(id(), eventId, ref, "A", "B", "t1"), List.of()));

        assertThat(result).isInstanceOf(CommitResult.Committed.class);
        assertThat(((CommitResult.Committed) result).version()).isEqualTo(1L);

        StateRecord found = store.find(ref).orElseThrow();
        assertThat(found.state()).isEqualTo("B");
        assertThat(found.version()).isEqualTo(1L);
    }

    @Test
    void refusalCommitDoesNotBumpVersionButAddsAuditAndInboxEntry() {
        // The standalone fallback creates a placeholder state document (no `state` field) to hold this entry;
        // find() still reports absent, matching the transactional mode and InMemoryStateStore (see class Javadoc).
        EntityRef ref = EntityRef.of("widget", "w-refusal");
        String eventId = id();
        AuditRecord audit = refused(id(), eventId, ref, null, RefusalReason.NO_MATCH, "no transition for this action");

        CommitResult result = store.commit(new Commit(ref, 0L, null, eventId, audit, List.of()));
        assertThat(result).isInstanceOf(CommitResult.Committed.class);
        assertThat(store.find(ref)).as("placeholder document is invisible to find()").isEmpty();

        AuditRecord stored = store.byEventId(eventId).orElseThrow();
        assertThat(stored.outcome()).isEqualTo(AuditOutcome.REFUSED);

        CommitResult replay = store.commit(new Commit(ref, 0L,  null, eventId,
                refused(id(), eventId, ref, null, RefusalReason.NO_MATCH, "no transition for this action"), List.of()));
        assertThat(replay).isInstanceOf(CommitResult.AlreadyApplied.class);
        assertThat(((CommitResult.AlreadyApplied) replay).firstAuditId()).isEqualTo(audit.auditId());
    }

    @Test
    void duplicateEventIdReturnsAlreadyAppliedWithTheFirstAuditId() {
        EntityRef ref = EntityRef.of("widget", "w-dup");
        String eventId = id();
        String firstAuditId = id();
        store.commit(new Commit(ref, 0L, "B", eventId, applied(firstAuditId, eventId, ref, "A", "B", "t1"), List.of()));

        CommitResult second = store.commit(new Commit(ref, 0L, "B", eventId, applied(id(), eventId, ref, "A", "B", "t1"), List.of()));
        assertThat(second).isInstanceOf(CommitResult.AlreadyApplied.class);
        assertThat(((CommitResult.AlreadyApplied) second).firstAuditId()).isEqualTo(firstAuditId);
    }

    @Test
    void duplicateEventIdWinsOverAStaleExpectedVersion() {
        // DD-07: inbox is checked before version, "regardless of version" — a redelivery whose caller thinks
        // the entity is still at version 0 must still be recognised as a duplicate, not reported as a conflict.
        EntityRef ref = EntityRef.of("widget", "w-dup-stale");
        String e1 = id();
        String firstAuditId = id();
        store.commit(new Commit(ref, 0L, "B", e1, applied(firstAuditId, e1, ref, "A", "B", "t1"), List.of()));
        assertThat(store.find(ref).orElseThrow().version()).isEqualTo(1L);

        CommitResult redelivered = store.commit(new Commit(ref, 0L, "B", e1, applied(id(), e1, ref, "A", "B", "t1"), List.of()));
        assertThat(redelivered).isInstanceOf(CommitResult.AlreadyApplied.class);
        assertThat(((CommitResult.AlreadyApplied) redelivered).firstAuditId()).isEqualTo(firstAuditId);
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

        assertThat(store.find(ref).orElseThrow().version()).isEqualTo(1L);
        assertThat(store.byEventId(staleEventId)).isEmpty();

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
        assertThat(outbox.unsent(10)).extracting(LifecycleEvent::eventId).contains(emitted.eventId());

        outbox.markSent(List.of(emitted.eventId()));
        assertThat(outbox.unsent(10)).extracting(LifecycleEvent::eventId).doesNotContain(emitted.eventId());
    }

    @Test
    void countInStateCountsAcrossOrWithinATenant() {
        commitNew(new EntityRef("acme", "widget", "a1"), "READY");
        commitNew(new EntityRef("acme", "widget", "a2"), "READY");
        commitNew(new EntityRef("globex", "widget", "g1"), "READY");

        assertThat(store.countInState(null, "widget", "READY")).hasValue(3L);
        assertThat(store.countInState("acme", "widget", "READY")).hasValue(2L);
    }

    private void commitNew(EntityRef ref, String toState) {
        String eventId = id();
        CommitResult r = store.commit(new Commit(ref, 0L, toState, eventId, applied(id(), eventId, ref, "A", toState, "t1"), List.of()));
        assertThat(r).isInstanceOf(CommitResult.Committed.class);
    }

    @Test
    void appendDetachedAddsAnAuditRowNotTiedToAVersion() {
        EntityRef ref = EntityRef.of("widget", "w-detached");
        AuditRecord row = TestSupport.conflicted(id(), id(), ref, "A", "conflict detail");
        store.appendDetached(row);

        assertThat(store.byEventId(row.eventId())).isPresent();
        assertThat(store.find(ref)).isEmpty();
    }

    @Test
    void auditQueriesByEntityByEventIdAndByCorrelation() {
        EntityRef ref = EntityRef.of("widget", "w-audit");
        String e1 = id();
        store.commit(new Commit(ref, 0L, "B", e1, applied(id(), e1, ref, "A", "B", "t1"), List.of()));

        assertThat(store.byEntity(ref)).extracting(AuditRecord::eventId).contains(e1);
        assertThat(store.byEventId(e1)).isPresent();
        assertThat(store.byCorrelation(e1)).extracting(AuditRecord::eventId).containsExactly(e1);
    }

    @Test
    void tenantEmptyStringAndNullRoundTripToTheSameRecord() {
        EntityRef refEmpty = new EntityRef("", "widget", "w-tenant");
        String eventId = id();
        store.commit(new Commit(refEmpty, 0L, "B", eventId, applied(id(), eventId, refEmpty, "A", "B", "t1"), List.of()));

        EntityRef refNull = new EntityRef(null, "widget", "w-tenant");
        StateRecord found = store.find(refNull).orElseThrow();
        assertThat(found.ref().tenantId()).isNull();
    }

    @Test
    void embeddedInboxIsCappedAtOneThousandMostRecentEntries() {
        EntityRef ref = EntityRef.of("widget", "w-cap");
        store.commit(new Commit(ref, 0L, "B", id(), applied(id(), id(), ref, "A", "B", "t1"), List.of()));
        // Refusals accumulate inbox entries without a state change, cheaply exercising the cap.
        for (int i = 0; i < MongoStateStore.MAX_EMBEDDED_INBOX + 10; i++) {
            String eventId = "refusal-" + i;
            store.commit(new Commit(ref, 1L, null, eventId,
                    refused(id(), eventId, ref, "B", RefusalReason.NO_MATCH, "no transition"), List.of()));
        }
        // The most recent entry must still be recognised (proves the cap keeps the *newest* entries).
        String lastEventId = "refusal-" + (MongoStateStore.MAX_EMBEDDED_INBOX + 9);
        CommitResult replay = store.commit(new Commit(ref, 1L, null, lastEventId,
                refused(id(), lastEventId, ref, "B", RefusalReason.NO_MATCH, "no transition"), List.of()));
        assertThat(replay).isInstanceOf(CommitResult.AlreadyApplied.class);
    }
}
