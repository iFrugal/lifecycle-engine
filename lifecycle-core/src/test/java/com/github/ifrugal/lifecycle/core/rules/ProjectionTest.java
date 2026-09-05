package com.github.ifrugal.lifecycle.core.rules;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** DD-04 projections: emitted payloads are `$`-references or literals resolved from the consumed event alone (H10). */
class ProjectionTest {

    private Projection.Context ctx() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("shipmentId", "s-1");
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("amount", 120);
        payload.put("payment", payment);
        return new Projection.Context(
                new EntityRef("acme", "order", "o-1"),
                payload,
                Actor.of("u1", "customer"),
                "evt-1",
                "NEW",
                "PAID");
    }

    @Test
    void wholePayloadReference() {
        Object v = Projection.project(Projection.PAYLOAD, ctx());
        assertThat(v).isEqualTo(ctx().payload());
    }

    @Test
    void dottedPayloadReferenceResolves() {
        assertThat(Projection.project("$payload.shipmentId", ctx())).isEqualTo("s-1");
        assertThat(Projection.project("$payload.payment.amount", ctx())).isEqualTo(120);
    }

    @Test
    void missingNestedPathResolvesToNull() {
        assertThat(Projection.project("$payload.payment.missing", ctx())).isNull();
        assertThat(Projection.project("$payload.nope.deeper", ctx())).isNull();
    }

    @Test
    void entityActorEventAndStateReferencesResolve() {
        Projection.Context c = ctx();
        assertThat(Projection.project(Projection.ENTITY_ID, c)).isEqualTo("o-1");
        assertThat(Projection.project(Projection.ENTITY_TYPE, c)).isEqualTo("order");
        assertThat(Projection.project(Projection.TENANT, c)).isEqualTo("acme");
        assertThat(Projection.project(Projection.ACTOR_ID, c)).isEqualTo("u1");
        assertThat(Projection.project(Projection.EVENT_ID, c)).isEqualTo("evt-1");
        assertThat(Projection.project(Projection.STATE_FROM, c)).isEqualTo("NEW");
        assertThat(Projection.project(Projection.STATE_TO, c)).isEqualTo("PAID");
    }

    @Test
    void tenantReferenceResolvesToNullWhenEntityHasNoTenant() {
        Projection.Context c = new Projection.Context(EntityRef.of("order", "o-1"), Map.of(), Actor.of("u"), "e", "A", "B");
        assertThat(Projection.project(Projection.TENANT, c)).isNull();
    }

    @Test
    void doubleDollarEscapesALiteralDollar() {
        assertThat(Projection.project("$$payload", ctx())).isEqualTo("$payload");
        assertThat(Projection.project("$$5", ctx())).isEqualTo("$5");
    }

    @Test
    void plainLiteralsPassThroughUnchanged() {
        assertThat(Projection.project("hello", ctx())).isEqualTo("hello");
        assertThat(Projection.project(42, ctx())).isEqualTo(42);
        assertThat(Projection.project(null, ctx())).isNull();
    }

    @Test
    void nestedMapsAndListsAreProjectedRecursively() {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("orderId", "$entity.id");
        template.put("tags", List.of("$actor.id", "literal"));
        Object projected = Projection.project(template, ctx());
        assertThat(projected).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) projected;
        assertThat(m.get("orderId")).isEqualTo("o-1");
        assertThat(m.get("tags")).isEqualTo(List.of("u1", "literal"));
    }

    @Test
    void invalidReferencesListsUnknownDollarPathsOnly() {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("ok1", "$entity.id");
        template.put("ok2", "$payload.shipmentId");
        template.put("bad", "$bogus.reference");
        template.put("literal", "not a reference");
        assertThat(Projection.invalidReferences(template)).containsExactly("$bogus.reference");
    }

    @Test
    void invalidReferencesEmptyForFullyValidTemplate() {
        Map<String, Object> template = Map.of("a", "$entity.id", "b", List.of("$payload", "$actor.id"));
        assertThat(Projection.invalidReferences(template)).isEmpty();
    }

    @Test
    void invalidReferencesIgnoresEscapedDollar() {
        assertThat(Projection.invalidReferences("$$notAReference.at.all")).isEmpty();
    }

    @Test
    void projectPayloadWrapsMapDirectly() {
        Map<String, Object> template = Map.of("orderId", "$entity.id");
        Map<String, Object> result = Projection.projectPayload(template, ctx());
        assertThat(result).containsEntry("orderId", "o-1");
    }

    @Test
    void projectPayloadWrapsNonMapResultUnderValue() {
        Map<String, Object> result = Projection.projectPayload("$actor.id", ctx());
        assertThat(result).containsExactly(Map.entry("value", "u1"));
    }

    @Test
    void projectPayloadOfNullTemplateIsEmptyMap() {
        assertThat(Projection.projectPayload(null, ctx())).isEmpty();
    }
}
