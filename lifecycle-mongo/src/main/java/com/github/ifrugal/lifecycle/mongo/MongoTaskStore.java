package com.github.ifrugal.lifecycle.mongo;

import com.github.ifrugal.lifecycle.api.model.Causation;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.tasks.Task;
import com.github.ifrugal.lifecycle.tasks.TaskStore;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * MongoDB implementation of {@link TaskStore}. {@link #transition} is a compare-and-set on {@code status} via
 * {@code findOneAndUpdate}, so two claimants or two completions racing the same task cannot both win.
 */
public final class MongoTaskStore implements TaskStore {

    private final MongoCollection<Document> collection;

    public MongoTaskStore(MongoDatabase database) {
        this.collection = Objects.requireNonNull(database, "database").getCollection(MongoCollections.TASK);
    }

    @Override
    public boolean create(Task task) {
        try {
            collection.insertOne(toDocument(task));
            return true;
        } catch (MongoException e) {
            if (isDuplicateKey(e)) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public Optional<Task> find(String taskId) {
        Document d = collection.find(Filters.eq("_id", taskId)).first();
        return d == null ? Optional.empty() : Optional.of(toTask(d));
    }

    @Override
    public boolean transition(String taskId, Task.Status expectedStatus, Task updated) {
        Document result = collection.findOneAndUpdate(
                Filters.and(Filters.eq("_id", taskId), Filters.eq("status", expectedStatus.name())),
                Updates.combine(
                        Updates.set("status", updated.status().name()),
                        Updates.set("claimedBy", updated.claimedBy()),
                        Updates.set("updatedAt", Date.from(updated.updatedAt()))),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return result != null;
    }

    @Override
    public List<Task> openFor(String tenantId, Set<String> roles, int limit) {
        String tenant = MongoMapping.normalizeTenant(tenantId);
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.in("status", Task.Status.OPEN.name(), Task.Status.CLAIMED.name()));
        if (tenant != null) {
            filters.add(Filters.eq("tenantId", tenant));
        }
        filters.add(Filters.in("assignTo", roles));
        List<Task> out = new ArrayList<>();
        for (Document d : collection.find(Filters.and(filters)).sort(Sorts.ascending("createdAt")).limit(limit)) {
            out.add(toTask(d));
        }
        return out;
    }

    @Override
    public List<Task> byEntity(EntityRef createdBy) {
        List<Task> out = new ArrayList<>();
        for (Document d : collection.find(Filters.and(
                Filters.eq("createdByTenantId", createdBy.tenantId()),
                Filters.eq("createdByType", createdBy.type()),
                Filters.eq("createdById", createdBy.id())))) {
            out.add(toTask(d));
        }
        return out;
    }

    private static Document toDocument(Task t) {
        Causation c = t.causation();
        Task.OnComplete oc = t.onComplete();
        return new Document("_id", t.taskId())
                .append("tenantId", MongoMapping.normalizeTenant(t.tenantId()))
                .append("name", t.name())
                .append("assignTo", new ArrayList<>(t.assignTo()))
                .append("createdByTenantId", t.createdBy().tenantId())
                .append("createdByType", t.createdBy().type())
                .append("createdById", t.createdBy().id())
                .append("createdByEventId", t.createdByEventId())
                .append("onCompleteAction", oc.action())
                .append("targetTenantId", oc.target().tenantId())
                .append("targetType", oc.target().type())
                .append("targetId", oc.target().id())
                .append("payload", new Document(t.payload()))
                .append("status", t.status().name())
                .append("claimedBy", t.claimedBy())
                .append("createdAt", Date.from(t.createdAt()))
                .append("updatedAt", Date.from(t.updatedAt()))
                .append("correlationId", c.correlationId())
                .append("causationId", c.causationId())
                .append("hop", c.hop());
    }

    @SuppressWarnings("unchecked")
    private static Task toTask(Document d) {
        List<String> assignTo = (List<String>) d.get("assignTo");
        EntityRef createdBy = new EntityRef(d.getString("createdByTenantId"), d.getString("createdByType"), d.getString("createdById"));
        EntityRef target = new EntityRef(d.getString("targetTenantId"), d.getString("targetType"), d.getString("targetId"));
        Task.OnComplete onComplete = new Task.OnComplete(d.getString("onCompleteAction"), target);
        Document payload = (Document) d.get("payload");
        Causation causation = new Causation(d.getString("correlationId"), d.getString("causationId"),
                d.get("hop") == null ? 0 : ((Number) d.get("hop")).intValue());
        Set<String> assignToSet = assignTo == null ? Set.of() : new HashSet<>(assignTo);
        return new Task(
                d.getString("_id"),
                d.getString("tenantId"),
                d.getString("name"),
                assignToSet,
                createdBy,
                d.getString("createdByEventId"),
                onComplete,
                payload == null ? null : payload,
                Task.Status.valueOf(d.getString("status")),
                d.getString("claimedBy"),
                MongoMapping.toInstant(d.getDate("createdAt")),
                MongoMapping.toInstant(d.getDate("updatedAt")),
                causation);
    }

    private static boolean isDuplicateKey(MongoException e) {
        if (e instanceof MongoWriteException we) {
            return we.getError().getCategory() == ErrorCategory.DUPLICATE_KEY;
        }
        return e.getCode() == 11000;
    }
}
