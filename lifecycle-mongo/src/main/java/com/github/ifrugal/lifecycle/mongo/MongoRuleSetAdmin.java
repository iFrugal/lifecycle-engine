package com.github.ifrugal.lifecycle.mongo;

import com.mongodb.MongoException;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.Date;
import java.util.Objects;

/**
 * Governance side of {@link MongoRuleSetSource} (DD-05): draft, activate, retire. Not on the engine's read path.
 * {@link #activate} retires whatever is currently ACTIVE for the same {@code (tenantId, entityType)} and sets the
 * given version ACTIVE in one transaction, so {@link MongoRuleSetSource#fetchActive()} never observes either zero
 * or two ACTIVE rows for that pair; the {@code lifecycleRuleSet_active} partial unique index (see
 * {@link MongoSchema}) is the last line of defence if it ever did.
 */
public final class MongoRuleSetAdmin {

    private final MongoClient client;
    private final MongoCollection<Document> ruleSetCollection;

    public MongoRuleSetAdmin(MongoClient client, MongoDatabase database) {
        this.client = Objects.requireNonNull(client, "client");
        this.ruleSetCollection = Objects.requireNonNull(database, "database").getCollection(MongoCollections.RULE_SET);
    }

    /** Inserts a DRAFT row. @return the generated row id */
    public String insertDraft(String tenantId, String entityType, int version, String format, String body, String createdBy) {
        String id = new ObjectId().toHexString();
        Document doc = new Document("_id", id)
                .append("tenantId", MongoMapping.normalizeTenant(tenantId))
                .append("entityType", Objects.requireNonNull(entityType, "entityType"))
                .append("version", version)
                .append("status", "DRAFT")
                .append("format", Objects.requireNonNull(format, "format"))
                .append("body", Objects.requireNonNull(body, "body"))
                .append("createdBy", Objects.requireNonNull(createdBy, "createdBy"))
                .append("createdAt", Date.from(Instant.now()))
                .append("activatedAt", null);
        ruleSetCollection.insertOne(doc);
        return id;
    }

    /**
     * Retires the current ACTIVE row for {@code (tenantId, entityType)}, if any, then marks {@code version}
     * ACTIVE. One transaction, retried on a MongoDB transient transaction error like {@link MongoStateStore} does.
     *
     * @throws IllegalArgumentException if no row exists for {@code (tenantId, entityType, version)}
     */
    public void activate(String tenantId, String entityType, int version) {
        String tenant = MongoMapping.normalizeTenant(tenantId);
        for (;;) {
            try (ClientSession session = client.startSession()) {
                session.startTransaction(TransactionOptions.builder().writeConcern(WriteConcern.MAJORITY).build());
                try {
                    ruleSetCollection.updateMany(session,
                            Filters.and(tenantFilter(tenant), Filters.eq("entityType", entityType), Filters.eq("status", "ACTIVE")),
                            Updates.set("status", "RETIRED"));
                    Document activated = ruleSetCollection.findOneAndUpdate(session,
                            Filters.and(tenantFilter(tenant), Filters.eq("entityType", entityType), Filters.eq("version", version)),
                            Updates.combine(Updates.set("status", "ACTIVE"), Updates.set("activatedAt", Date.from(Instant.now()))));
                    if (activated == null) {
                        session.abortTransaction();
                        throw new IllegalArgumentException(
                                "no rule set row for tenantId=" + tenantId + ", entityType=" + entityType + ", version=" + version);
                    }
                    session.commitTransaction();
                    return;
                } catch (MongoException e) {
                    safeAbort(session);
                    if (e.hasErrorLabel("TransientTransactionError")) {
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

    /** Marks a row RETIRED outright (no replacement activated). */
    public void retire(String tenantId, String entityType, int version) {
        String tenant = MongoMapping.normalizeTenant(tenantId);
        ruleSetCollection.updateOne(
                Filters.and(tenantFilter(tenant), Filters.eq("entityType", entityType), Filters.eq("version", version)),
                Updates.set("status", "RETIRED"));
    }

    private static Bson tenantFilter(String tenant) {
        return Filters.eq("tenantId", tenant);
    }

    private static void safeAbort(ClientSession session) {
        try {
            session.abortTransaction();
        } catch (RuntimeException ignore) {
            // best-effort; the session is being discarded either way
        }
    }
}
