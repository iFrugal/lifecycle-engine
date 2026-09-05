package com.github.ifrugal.lifecycle.mongo;

import com.github.ifrugal.lifecycle.api.model.AuditRecord;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.StateRecord;
import com.github.ifrugal.lifecycle.api.spi.AuditQuery;
import com.github.ifrugal.lifecycle.api.spi.Commit;
import com.github.ifrugal.lifecycle.api.spi.CommitResult;
import com.github.ifrugal.lifecycle.api.spi.Outbox;
import com.github.ifrugal.lifecycle.api.spi.StateStore;
import com.github.ifrugal.lifecycle.json.LifecycleJson;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.PushOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * MongoDB implementation of the storage seam (DD-07, DD-11). Two modes, chosen at construction:
 *
 * <h2>Transactional mode ({@code useTransactions = true})</h2>
 * Requires a replica set (a {@code MongoDBContainer} in tests starts a single-node one so multi-document
 * transactions work). {@link #commit} runs inbox insert, version check, audit insert and outbox insert in one
 * session transaction, exactly mirroring {@code InMemoryStateStore}'s order: inbox first (a replay is a replay
 * regardless of version), then version, then audit, then state, then outbox. A MongoDB write conflict between two
 * transactions racing the same document surfaces as a {@code TransientTransactionError}; per MongoDB's own
 * guidance this requires retrying the *whole* transaction from a fresh session, which {@link #commit} does
 * internally (bounded only by giving up the CPU, never by a retry-count ceiling, since a transient error here is
 * expected traffic under contention, not a fault) — callers only ever see a {@link CommitResult}, never a thrown
 * driver exception, for this reason.
 *
 * <h2>Standalone mode ({@code useTransactions = false})</h2>
 * DD-11's documented fallback for a MongoDB deployment with no replica set (so no multi-document transactions).
 * The state document becomes the sole unit of atomicity: the inbox tail (capped at the {@value #MAX_EMBEDDED_INBOX}
 * most recent {@code {eventId, auditId}} entries) and the not-yet-sent outbox are embedded arrays on the state
 * document itself, and a single conditional {@code findOneAndUpdate} — filtered on both {@code version} and
 * "no embedded inbox entry already carries this eventId" — is the one atomic write. The audit row is written
 * second, keyed by the audit id the caller supplied, so it is naturally idempotent if a process crashes between
 * the state write and the audit write and the exact same {@link Commit} is retried.
 * <p><b>Limits of the fallback</b> (document these to callers choosing this mode):
 * <ul>
 *   <li>Inbox dedupe only remembers the last {@value #MAX_EMBEDDED_INBOX} events per entity, not a time-based
 *       retention window; an old-enough replay is no longer recognised as a duplicate (falls through to the
 *       machine, which will usually — but is not guaranteed to — refuse it with {@code NO_MATCH}).</li>
 *   <li>A refusal on an entity with no prior applied transition still needs somewhere to record the inbox entry,
 *       so a placeholder state document is created — one with no {@code state} field. {@link #find} treats a
 *       document with no {@code state} field as absent, so this is invisible to callers, but it does mean a
 *       document can exist in the collection for an entity that, from the API's point of view, has never been
 *       seen.</li>
 *   <li>A crash between the state write and the audit write leaves a state whose {@code lastEventId} has no
 *       matching audit row until the identical commit is retried; {@link #appendDetached} and conflict handling
 *       are unaffected since they never touch the embedded arrays.</li>
 *   <li>No cross-transaction write-conflict retries are needed here (a single document write is already atomic),
 *       but there is also no protection against two *different* logical commits interleaving across two
 *       *different* entities the way a multi-document transaction would offer — this mode only ever needed to
 *       protect one entity's own document, which is exactly what it does.</li>
 * </ul>
 */
public final class MongoStateStore implements StateStore, AuditQuery {

    static final int MAX_EMBEDDED_INBOX = 1000;

    private final MongoClient client;
    private final Duration inboxRetention;
    private final boolean useTransactions;
    private final MongoCollection<Document> stateCollection;
    private final MongoCollection<Document> inboxCollection;
    private final MongoCollection<Document> auditCollection;
    private final MongoCollection<Document> outboxCollection;
    private final MongoOutbox outbox;

    public MongoStateStore(MongoClient client, MongoDatabase database, Duration inboxRetention, boolean useTransactions) {
        this.client = Objects.requireNonNull(client, "client");
        Objects.requireNonNull(database, "database");
        this.inboxRetention = Objects.requireNonNull(inboxRetention, "inboxRetention");
        this.useTransactions = useTransactions;
        this.stateCollection = database.getCollection(MongoCollections.STATE);
        this.inboxCollection = database.getCollection(MongoCollections.INBOX);
        this.auditCollection = database.getCollection(MongoCollections.AUDIT);
        this.outboxCollection = database.getCollection(MongoCollections.OUTBOX);
        this.outbox = new MongoOutbox(outboxCollection, stateCollection, useTransactions);
    }

    @Override
    public Optional<StateRecord> find(EntityRef ref) {
        Document d = stateCollection.find(Filters.eq("_id", ref.key())).first();
        if (d == null || d.get("state") == null) {
            // Absent, or a standalone-mode placeholder created only to hold an embedded inbox entry (see class doc).
            return Optional.empty();
        }
        return Optional.of(MongoMapping.documentToState(d));
    }

    @Override
    public CommitResult commit(Commit c) {
        return useTransactions ? commitTransactional(c) : commitStandalone(c);
    }

    @Override
    public void appendDetached(AuditRecord record) {
        auditCollection.insertOne(MongoMapping.auditToDocument(record));
    }

    @Override
    public OptionalLong countInState(String tenantId, String entityType, String state) {
        String tenant = MongoMapping.normalizeTenant(tenantId);
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("entityType", entityType));
        filters.add(Filters.eq("state", state));
        if (tenant != null) {
            filters.add(Filters.eq("tenantId", tenant));
        }
        return OptionalLong.of(stateCollection.countDocuments(Filters.and(filters)));
    }

    @Override
    public Optional<Outbox> outbox() {
        return Optional.of(outbox);
    }

    /**
     * Deletes expired rows from the dedicated inbox collection (transactional mode only; MongoDB's own TTL
     * monitor also does this in the background, roughly every 60 seconds, so this is mainly useful for
     * deterministic tests and operational visibility). A no-op in standalone mode, where the inbox is embedded
     * and pruned by the {@value #MAX_EMBEDDED_INBOX}-entry cap instead of by time.
     *
     * @return the number of rows deleted
     */
    public long purgeExpiredInbox() {
        if (!useTransactions) {
            return 0L;
        }
        return inboxCollection.deleteMany(Filters.lt("expiresAt", Date.from(Instant.now()))).getDeletedCount();
    }

    @Override
    public List<AuditRecord> byEntity(EntityRef ref) {
        List<AuditRecord> out = new ArrayList<>();
        for (Document d : auditCollection.find(Filters.and(
                Filters.eq("tenantId", ref.tenantId()),
                Filters.eq("entityType", ref.type()),
                Filters.eq("entityId", ref.id()))).sort(Sorts.ascending("at"))) {
            out.add(MongoMapping.documentToAudit(d));
        }
        return out;
    }

    @Override
    public Optional<AuditRecord> byEventId(String eventId) {
        Document d = auditCollection.find(Filters.eq("eventId", eventId)).first();
        return d == null ? Optional.empty() : Optional.of(MongoMapping.documentToAudit(d));
    }

    @Override
    public List<AuditRecord> byCorrelation(String correlationId) {
        List<AuditRecord> out = new ArrayList<>();
        for (Document d : auditCollection.find(Filters.eq("correlationId", correlationId)).sort(Sorts.ascending("hop"))) {
            out.add(MongoMapping.documentToAudit(d));
        }
        return out;
    }

    // ---------------------------------------------------------------- transactional mode

    /** Thrown to unwind out of {@link #attemptTransactional} once the outcome is decided but must not be committed. */
    private static final class Abort extends RuntimeException {
        final CommitResult result;
        Abort(CommitResult result) {
            super(null, null, false, false);
            this.result = result;
        }
    }

    private CommitResult commitTransactional(Commit c) {
        int attempt = 0;
        for (;;) {
            try (ClientSession session = client.startSession()) {
                session.startTransaction(TransactionOptions.builder().writeConcern(WriteConcern.ACKNOWLEDGED).build());
                try {
                    CommitResult outcome = attemptTransactional(session, c);
                    session.commitTransaction();
                    return outcome;
                } catch (Abort a) {
                    session.abortTransaction();
                    return a.result;
                } catch (MongoException e) {
                    safeAbort(session);
                    if (isTransient(e)) {
                        // A write conflict on the state document means another commit for this entity is
                        // winning or has won. That is exactly the situation DD-07 calls a version conflict, so
                        // after a few short, jittered retries we stop competing and report VersionMismatch;
                        // the Dispatcher re-reads and retries from step 1, or the transport redelivers.
                        // Retrying the whole transaction forever is a livelock under real contention.
                        attempt++;
                        if (attempt > MAX_TRANSIENT_RETRIES) {
                            return new CommitResult.VersionMismatch(c.expectedVersion(), readVersionOutsideTransaction(c.ref().key()));
                        }
                        backoff(attempt);
                        continue;
                    }
                    throw e;
                } catch (RuntimeException ex) {
                    safeAbort(session);
                    throw ex;
                }
            }
        }
    }

    private static void backoff(int attempt) {
        int capMs = Math.min(25, attempt * 2);
        int jitterMs = java.util.concurrent.ThreadLocalRandom.current().nextInt(capMs + 1);
        if (jitterMs <= 0) {
            return;
        }
        try {
            Thread.sleep(jitterMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private CommitResult attemptTransactional(ClientSession session, Commit c) {
        String stateKey = c.ref().key();
        String inboxKey = stateKey + "|" + c.eventId();
        Instant now = Instant.now();

        // 1. inbox: a replay is a replay regardless of version.
        Document inboxDoc = new Document("_id", inboxKey)
                .append("tenantId", c.ref().tenantId())
                .append("entityType", c.ref().type())
                .append("entityId", c.ref().id())
                .append("eventId", c.eventId())
                .append("auditId", c.audit().auditId())
                .append("expiresAt", Date.from(now.plus(inboxRetention)));
        // Inside a transaction MongoDB may report a duplicate _id as a WriteConflict carrying the
        // TransientTransactionError label rather than as a plain duplicate-key error, so read first; the catch
        // below remains as the fallback for the racing-insert case.
        Document already = inboxCollection.find(session, Filters.eq("_id", inboxKey)).first();
        if (already != null) {
            throw new Abort(new CommitResult.AlreadyApplied(already.getString("auditId")));
        }
        try {
            inboxCollection.insertOne(session, inboxDoc);
        } catch (MongoException e) {
            if (!isDuplicateKey(e)) {
                throw e;
            }
            Document existing = inboxCollection.find(session, Filters.eq("_id", inboxKey)).first();
            String firstAuditId = existing != null ? existing.getString("auditId") : c.audit().auditId();
            throw new Abort(new CommitResult.AlreadyApplied(firstAuditId));
        }

        // 2. version, then 4. state (interleaved: the write IS the check).
        long expected = c.expectedVersion();
        long newVersion;
        if (c.advances()) {
            Document setFields = new Document("state", c.nextState())
                    .append("version", expected + 1)
                    .append("updatedAt", Date.from(now))
                    .append("lastEventId", c.eventId())
                    .append("ruleSetVersion", c.audit().ruleSetVersion());
            Document updated = stateCollection.findOneAndUpdate(session,
                    Filters.and(Filters.eq("_id", stateKey), Filters.eq("version", expected)),
                    new Document("$set", setFields),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            if (updated != null) {
                newVersion = expected + 1;
            } else if (expected == 0L && stateCollection.find(session, Filters.eq("_id", stateKey)).first() == null) {
                Document fresh = new Document("_id", stateKey)
                        .append("tenantId", c.ref().tenantId())
                        .append("entityType", c.ref().type())
                        .append("entityId", c.ref().id());
                fresh.putAll(setFields);
                try {
                    stateCollection.insertOne(session, fresh);
                    newVersion = 1L;
                } catch (MongoException e) {
                    if (!isDuplicateKey(e)) {
                        throw e;
                    }
                    throw new Abort(new CommitResult.VersionMismatch(0L, readVersion(session, stateKey)));
                }
            } else {
                throw new Abort(new CommitResult.VersionMismatch(expected, readVersion(session, stateKey)));
            }
        } else {
            long actual = readVersion(session, stateKey);
            if (actual != expected) {
                throw new Abort(new CommitResult.VersionMismatch(expected, actual));
            }
            newVersion = actual;
        }

        // 3. audit (also for refusals).
        auditCollection.insertOne(session, MongoMapping.auditToDocument(c.audit()));

        // 5. outbox.
        if (!c.outbox().isEmpty()) {
            List<Document> docs = c.outbox().stream()
                    .map(e -> new Document("_id", e.eventId())
                            .append("body", LifecycleJson.write(e))
                            .append("createdAt", Date.from(now))
                            .append("sentAt", null))
                    .toList();
            outboxCollection.insertMany(session, docs);
        }

        return new CommitResult.Committed(newVersion, c.audit().auditId());
    }

    /** How many whole-transaction retries a write conflict gets before it is reported as a version conflict. */
    static final int MAX_TRANSIENT_RETRIES = 3;

    private long readVersionOutsideTransaction(String stateKey) {
        Document d = stateCollection.find(Filters.eq("_id", stateKey)).first();
        return d == null || d.get("version") == null ? 0L : ((Number) d.get("version")).longValue();
    }

    private long readVersion(ClientSession session, String stateKey) {
        Document d = stateCollection.find(session, Filters.eq("_id", stateKey)).first();
        return d == null || d.get("version") == null ? 0L : ((Number) d.get("version")).longValue();
    }

    private static void safeAbort(ClientSession session) {
        try {
            session.abortTransaction();
        } catch (RuntimeException ignore) {
            // best-effort; the session is being discarded either way
        }
    }

    private static boolean isTransient(MongoException e) {
        return e.hasErrorLabel("TransientTransactionError");
    }

    // ---------------------------------------------------------------- standalone mode

    private CommitResult commitStandalone(Commit c) {
        String stateKey = c.ref().key();
        Instant now = Instant.now();
        Document inboxEntry = new Document("eventId", c.eventId()).append("auditId", c.audit().auditId());

        List<Bson> ops = new ArrayList<>();
        ops.add(Updates.pushEach("inbox", List.of(inboxEntry), new PushOptions().slice(-MAX_EMBEDDED_INBOX)));
        if (!c.outbox().isEmpty()) {
            List<Document> outboxDocs = c.outbox().stream()
                    .map(e -> new Document("eventId", e.eventId())
                            .append("body", LifecycleJson.write(e))
                            .append("createdAt", Date.from(now)))
                    .toList();
            ops.add(Updates.pushEach("pendingOutbox", outboxDocs));
        }
        ops.add(Updates.setOnInsert("tenantId", c.ref().tenantId()));
        ops.add(Updates.setOnInsert("entityType", c.ref().type()));
        ops.add(Updates.setOnInsert("entityId", c.ref().id()));

        long expected = c.expectedVersion();
        long newVersion = expected;
        if (c.advances()) {
            newVersion = expected + 1;
            ops.add(Updates.set("state", c.nextState()));
            ops.add(Updates.set("version", newVersion));
            ops.add(Updates.set("updatedAt", Date.from(now)));
            ops.add(Updates.set("lastEventId", c.eventId()));
            ops.add(Updates.set("ruleSetVersion", c.audit().ruleSetVersion()));
        }

        Bson filter = Filters.and(
                Filters.eq("_id", stateKey),
                Filters.eq("version", expected),
                Filters.ne("inbox.eventId", c.eventId()));
        FindOneAndUpdateOptions opts = new FindOneAndUpdateOptions()
                .upsert(expected == 0L)
                .returnDocument(ReturnDocument.AFTER);

        Document result;
        try {
            result = stateCollection.findOneAndUpdate(filter, Updates.combine(ops), opts);
        } catch (MongoException e) {
            if (!isDuplicateKey(e)) {
                throw e;
            }
            return disambiguate(stateCollection.find(Filters.eq("_id", stateKey)).first(), c);
        }
        if (result == null) {
            return disambiguate(stateCollection.find(Filters.eq("_id", stateKey)).first(), c);
        }

        try {
            auditCollection.insertOne(MongoMapping.auditToDocument(c.audit()));
        } catch (MongoException e) {
            if (!isDuplicateKey(e)) {
                throw e;
            }
            // Already recorded: a crash between the state write and the audit write, retried with the same
            // Commit (same auditId). Idempotent no-op, per this class's Javadoc.
        }
        return new CommitResult.Committed(newVersion, c.audit().auditId());
    }

    @SuppressWarnings("unchecked")
    private static CommitResult disambiguate(Document existing, Commit c) {
        if (existing == null) {
            return new CommitResult.VersionMismatch(c.expectedVersion(), 0L);
        }
        // DD-07: the inbox is checked first, so a replay is recognised regardless of version — a redelivered
        // event whose caller's view of the version is now stale must still come back AlreadyApplied, not
        // VersionMismatch. Look for the eventId in the embedded inbox before comparing versions.
        List<Document> inbox = (List<Document>) existing.get("inbox");
        if (inbox != null) {
            for (Document entry : inbox) {
                if (c.eventId().equals(entry.getString("eventId"))) {
                    return new CommitResult.AlreadyApplied(entry.getString("auditId"));
                }
            }
        }
        long actual = existing.get("version") == null ? 0L : ((Number) existing.get("version")).longValue();
        return new CommitResult.VersionMismatch(c.expectedVersion(), actual);
    }

    // ---------------------------------------------------------------- shared

    private static boolean isDuplicateKey(MongoException e) {
        if (e instanceof MongoWriteException we) {
            return we.getError().getCategory() == ErrorCategory.DUPLICATE_KEY;
        }
        if (e instanceof MongoBulkWriteException be) {
            return be.getWriteErrors().stream().anyMatch(we -> we.getCategory() == ErrorCategory.DUPLICATE_KEY);
        }
        return e.getCode() == 11000;
    }
}
