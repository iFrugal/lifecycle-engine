package com.github.ifrugal.lifecycle.mongo;

import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.spi.Outbox;
import com.github.ifrugal.lifecycle.json.LifecycleJson;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

/**
 * The {@link Outbox} view of {@link MongoStateStore}. In transactional mode this is the dedicated
 * {@code lifecycleOutbox} collection (one row per event, {@code sentAt} nullable). In standalone mode there is no
 * separate collection: events sit in the {@code pendingOutbox} array embedded in the owning state document (see
 * {@link MongoStateStore}'s class Javadoc), so {@link #unsent} scans across state documents and {@link #markSent}
 * pulls the acknowledged entries out of them. Ordering across entities is best-effort in that mode.
 */
final class MongoOutbox implements Outbox {

    private final MongoCollection<Document> outboxCollection;
    private final MongoCollection<Document> stateCollection;
    private final boolean useTransactions;

    MongoOutbox(MongoCollection<Document> outboxCollection, MongoCollection<Document> stateCollection, boolean useTransactions) {
        this.outboxCollection = outboxCollection;
        this.stateCollection = stateCollection;
        this.useTransactions = useTransactions;
    }

    @Override
    public List<LifecycleEvent> unsent(int limit) {
        return useTransactions ? unsentTransactional(limit) : unsentStandalone(limit);
    }

    private List<LifecycleEvent> unsentTransactional(int limit) {
        List<LifecycleEvent> out = new ArrayList<>();
        for (Document d : outboxCollection.find(Filters.eq("sentAt", null)).sort(Sorts.ascending("createdAt")).limit(limit)) {
            out.add(LifecycleJson.readEvent(d.getString("body")));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<LifecycleEvent> unsentStandalone(int limit) {
        List<LifecycleEvent> out = new ArrayList<>();
        for (Document stateDoc : stateCollection.find(Filters.exists("pendingOutbox.0"))) {
            List<Document> pending = (List<Document>) stateDoc.get("pendingOutbox");
            if (pending == null) {
                continue;
            }
            for (Document p : pending) {
                out.add(LifecycleJson.readEvent(p.getString("body")));
                if (out.size() >= limit) {
                    return out;
                }
            }
        }
        return out;
    }

    @Override
    public void markSent(Collection<String> eventIds) {
        if (eventIds.isEmpty()) {
            return;
        }
        if (useTransactions) {
            outboxCollection.updateMany(Filters.in("_id", eventIds), Updates.set("sentAt", new Date()));
        } else {
            stateCollection.updateMany(
                    Filters.elemMatch("pendingOutbox", Filters.in("eventId", eventIds)),
                    Updates.pull("pendingOutbox", Filters.in("eventId", eventIds)));
        }
    }
}
