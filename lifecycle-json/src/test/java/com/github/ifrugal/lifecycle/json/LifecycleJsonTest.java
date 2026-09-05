package com.github.ifrugal.lifecycle.json;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.AuditOutcome;
import com.github.ifrugal.lifecycle.api.model.AuditRecord;
import com.github.ifrugal.lifecycle.api.model.Causation;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.EventKind;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.RefusalReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LifecycleJsonTest {

    @Test
    void eventRoundTripsWithNestedPayloadTimersAndCausation() {
        Instant at = Instant.parse("2026-09-05T10:15:30.123456Z");
        LifecycleEvent e = new LifecycleEvent("e-1", EventKind.SIGNAL, new EntityRef("acme", "thing", "t-1"), "GO",
                new Actor("u-1", java.util.Set.of("role-a", "role-b"), Actor.Kind.HUMAN),
                Map.of("nested", Map.of("n", 1, "list", List.of("x", 2.5, true)), "flag", false),
                at, at.plusSeconds(3600), new Causation("corr", "parent", 3), 7L);

        String json = LifecycleJson.write(e);
        LifecycleEvent back = LifecycleJson.readEvent(json);

        assertThat(back).isEqualTo(e);
        assertThat(json).contains("\"occurredAt\":\"2026-09-05T10:15:30.123456Z\"").doesNotContain("null");
    }

    @Test
    void nullOptionalFieldsAreOmittedAndReadBackAsNull() {
        LifecycleEvent e = LifecycleEvent.builder().entity(EntityRef.of("thing", "t-2")).action("GO").actor(Actor.of("u")).build();
        String json = LifecycleJson.write(e);
        assertThat(json).doesNotContain("deliverAt").doesNotContain("expectedVersion").doesNotContain("tenantId");
        LifecycleEvent back = LifecycleJson.readEvent(json);
        assertThat(back.deliverAt()).isNull();
        assertThat(back.expectedVersion()).isNull();
        assertThat(back.entity().tenantId()).isNull();
        assertThat(back.causation()).isEqualTo(Causation.root(e.eventId()));
    }

    @Test
    void unknownFieldsFromANewerProducerAreIgnored() {
        LifecycleEvent e = LifecycleEvent.builder().entity(EntityRef.of("thing", "t-3")).action("GO").actor(Actor.of("u")).build();
        String json = LifecycleJson.write(e).replaceFirst("\\{", "{\"futureField\":{\"a\":1},");
        assertThat(LifecycleJson.readEvent(json).action()).isEqualTo("GO");
    }

    @Test
    void auditRoundTrips() {
        AuditRecord a = new AuditRecord("a-1", "e-1", EntityRef.of("thing", "t-1"), "GO", Actor.of("u", "r"), "S1", null, null,
                AuditOutcome.REFUSED, RefusalReason.ROLE_DENIED, "actor lacks role", "v1", Instant.parse("2026-01-01T00:00:00Z"), Causation.root("e-1"));
        assertThat(LifecycleJson.readAudit(LifecycleJson.write(a))).isEqualTo(a);
    }

    @Test
    void malformedJsonIsAClearError() {
        assertThatThrownBy(() -> LifecycleJson.readEvent("{not json"))
                .isInstanceOf(LifecycleJson.JsonCodecException.class)
                .hasMessageContaining("LifecycleEvent");
    }
}
