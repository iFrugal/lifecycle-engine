package com.github.ifrugal.lifecycle.mongo;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.AuditOutcome;
import com.github.ifrugal.lifecycle.api.model.AuditRecord;
import com.github.ifrugal.lifecycle.api.model.Causation;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.RefusalReason;

import java.time.Instant;
import java.util.UUID;

/** Small fixture helpers shared by the tests in this package. Not itself a test. */
final class TestSupport {

    private TestSupport() {}

    static String id() {
        return UUID.randomUUID().toString();
    }

    static Actor actor(String id, String... roles) {
        return Actor.of(id, roles);
    }

    static AuditRecord applied(String auditId, String eventId, EntityRef entity, String from, String to, String transitionId) {
        return new AuditRecord(auditId, eventId, entity, "SOME_ACTION", actor("u1", "customer"), from, to, transitionId,
                AuditOutcome.APPLIED, null, null, "v1", Instant.now(), Causation.root(eventId));
    }

    static AuditRecord refused(String auditId, String eventId, EntityRef entity, String from, RefusalReason reason, String detail) {
        return new AuditRecord(auditId, eventId, entity, "SOME_ACTION", actor("u1", "customer"), from, null, null,
                AuditOutcome.REFUSED, reason, detail, "v1", Instant.now(), Causation.root(eventId));
    }

    static AuditRecord conflicted(String auditId, String eventId, EntityRef entity, String from, String detail) {
        return new AuditRecord(auditId, eventId, entity, "SOME_ACTION", actor("u1", "customer"), from, null, null,
                AuditOutcome.CONFLICTED, null, detail, "v1", Instant.now(), Causation.root(eventId));
    }
}
