package com.github.ifrugal.lifecycle.mongo;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;

import java.util.concurrent.TimeUnit;

/**
 * Creates every index this module relies on. Idempotent: safe to call on every application start
 * ({@code createIndex} is a no-op when an equivalent index already exists).
 *
 * <p>{@code _id} is the primary key for every collection (state: {@code EntityRef.key()}; inbox: the state key
 * plus {@code "|"} plus the event id; audit: the audit id; outbox: the event id; task: the task id) and needs no
 * extra index. Everything below is a secondary index the query patterns in this module need.
 */
public final class MongoSchema {

    private MongoSchema() {}

    public static void ensureIndexes(MongoDatabase database) {
        MongoCollection<Document> state = database.getCollection(MongoCollections.STATE);
        state.createIndex(Indexes.ascending("entityType", "state", "tenantId"));

        MongoCollection<Document> inbox = database.getCollection(MongoCollections.INBOX);
        // TTL: MongoDB's background reaper removes a document once expiresAt is in the past. Only ever
        // populated in transactional mode; the standalone fallback embeds a capped inbox in the state document.
        inbox.createIndex(Indexes.ascending("expiresAt"), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));

        MongoCollection<Document> audit = database.getCollection(MongoCollections.AUDIT);
        audit.createIndex(Indexes.ascending("entityType", "entityId", "at"));
        audit.createIndex(Indexes.ascending("eventId"));
        audit.createIndex(Indexes.ascending("correlationId", "hop"));

        MongoCollection<Document> outbox = database.getCollection(MongoCollections.OUTBOX);
        outbox.createIndex(Indexes.ascending("sentAt", "createdAt"));

        MongoCollection<Document> ruleSet = database.getCollection(MongoCollections.RULE_SET);
        ruleSet.createIndex(Indexes.ascending("tenantId", "entityType", "version"), new IndexOptions().unique(true));
        ruleSet.createIndex(Indexes.ascending("tenantId", "entityType"),
                new IndexOptions().unique(true).partialFilterExpression(Filters.eq("status", "ACTIVE")));

        MongoCollection<Document> task = database.getCollection(MongoCollections.TASK);
        task.createIndex(Indexes.ascending("status", "tenantId", "createdAt"));
        task.createIndex(Indexes.ascending("createdByType", "createdById"));
    }
}
