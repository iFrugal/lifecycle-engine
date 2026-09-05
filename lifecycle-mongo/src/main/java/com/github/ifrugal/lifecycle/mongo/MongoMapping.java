package com.github.ifrugal.lifecycle.mongo;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.AuditOutcome;
import com.github.ifrugal.lifecycle.api.model.AuditRecord;
import com.github.ifrugal.lifecycle.api.model.Causation;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.RefusalReason;
import com.github.ifrugal.lifecycle.api.model.StateRecord;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Document <-> api-record mapping shared by the store, the rule set source and the task store. Field names are
 * camelCase and mirror lifecycle-jdbc's {@code schema-postgresql.sql} column names. */
final class MongoMapping {

    private MongoMapping() {}

    static Date toDate(Instant i) {
        return i == null ? null : Date.from(i);
    }

    static Instant toInstant(Date d) {
        return d == null ? null : d.toInstant();
    }

    /** Normalises a blank tenant id to null, matching {@link EntityRef}'s own canonical constructor. */
    static String normalizeTenant(String tenantId) {
        return (tenantId == null || tenantId.isBlank()) ? null : tenantId;
    }

    static Document auditToDocument(AuditRecord a) {
        Causation c = a.causation();
        return new Document("_id", a.auditId())
                .append("eventId", a.eventId())
                .append("tenantId", a.entity().tenantId())
                .append("entityType", a.entity().type())
                .append("entityId", a.entity().id())
                .append("action", a.action())
                .append("actorId", a.actor().id())
                .append("actorRoles", new ArrayList<>(a.actor().roles()))
                .append("actorKind", a.actor().kind().name())
                .append("fromState", a.fromState())
                .append("toState", a.toState())
                .append("transitionId", a.transitionId())
                .append("outcome", a.outcome().name())
                .append("reason", a.reason() == null ? null : a.reason().name())
                .append("detail", a.detail())
                .append("ruleSetVersion", a.ruleSetVersion())
                .append("at", toDate(a.at()))
                .append("correlationId", c == null ? null : c.correlationId())
                .append("causationId", c == null ? null : c.causationId())
                .append("hop", c == null ? 0 : c.hop());
    }

    @SuppressWarnings("unchecked")
    static AuditRecord documentToAudit(Document d) {
        EntityRef entity = new EntityRef(d.getString("tenantId"), d.getString("entityType"), d.getString("entityId"));
        List<String> roles = (List<String>) d.get("actorRoles");
        Set<String> roleSet = roles == null ? Set.of() : new HashSet<>(roles);
        Actor actor = new Actor(d.getString("actorId"), roleSet, Actor.Kind.valueOf(d.getString("actorKind")));
        String correlationId = d.getString("correlationId");
        Causation causation = correlationId == null ? null
                : new Causation(correlationId, d.getString("causationId"), d.get("hop") == null ? 0 : ((Number) d.get("hop")).intValue());
        String reason = d.getString("reason");
        return new AuditRecord(
                d.getString("_id"),
                d.getString("eventId"),
                entity,
                d.getString("action"),
                actor,
                d.getString("fromState"),
                d.getString("toState"),
                d.getString("transitionId"),
                AuditOutcome.valueOf(d.getString("outcome")),
                reason == null ? null : RefusalReason.valueOf(reason),
                d.getString("detail"),
                d.getString("ruleSetVersion"),
                toInstant(d.getDate("at")),
                causation);
    }

    static StateRecord documentToState(Document d) {
        EntityRef ref = new EntityRef(d.getString("tenantId"), d.getString("entityType"), d.getString("entityId"));
        long version = d.get("version") == null ? 0L : ((Number) d.get("version")).longValue();
        return new StateRecord(ref, d.getString("state"), version, toInstant(d.getDate("updatedAt")),
                d.getString("lastEventId"), d.getString("ruleSetVersion"));
    }
}
