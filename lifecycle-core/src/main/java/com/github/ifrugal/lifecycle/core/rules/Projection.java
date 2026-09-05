package com.github.ifrugal.lifecycle.core.rules;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.Payloads;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves emitted-payload templates against the consumed event (DD-04). Values are literals or
 * {@code $}-references; nothing else resolves, so an emitted event is assembled from the consumed event alone (H10).
 */
public final class Projection {

    public static final String REF = "$";
    public static final String ESCAPE = "$$";
    public static final String PAYLOAD = "$payload";
    public static final String ENTITY_ID = "$entity.id";
    public static final String ENTITY_TYPE = "$entity.type";
    public static final String TENANT = "$tenant";
    public static final String ACTOR_ID = "$actor.id";
    public static final String EVENT_ID = "$event.id";
    public static final String STATE_FROM = "$state.from";
    public static final String STATE_TO = "$state.to";

    private Projection() {}

    public record Context(EntityRef entity, Map<String, Object> payload, Actor actor, String eventId, String from, String to) {}

    /** Every {@code $}-reference in the template that would not resolve. Empty means the template is valid. */
    public static List<String> invalidReferences(Object template) {
        List<String> bad = new ArrayList<>();
        collectInvalid(template, bad);
        return bad;
    }

    private static void collectInvalid(Object t, List<String> bad) {
        if (t instanceof String s && s.startsWith(REF) && !s.startsWith(ESCAPE)) {
            if (!isKnown(s)) {
                bad.add(s);
            }
        } else if (t instanceof Map<?, ?> m) {
            m.values().forEach(v -> collectInvalid(v, bad));
        } else if (t instanceof Collection<?> c) {
            c.forEach(v -> collectInvalid(v, bad));
        }
    }

    private static boolean isKnown(String ref) {
        return ref.equals(PAYLOAD) || ref.startsWith(PAYLOAD + ".")
                || ref.equals(ENTITY_ID) || ref.equals(ENTITY_TYPE) || ref.equals(TENANT)
                || ref.equals(ACTOR_ID) || ref.equals(EVENT_ID)
                || ref.equals(STATE_FROM) || ref.equals(STATE_TO);
    }

    public static Object project(Object template, Context ctx) {
        if (template instanceof String s) {
            if (s.startsWith(ESCAPE)) {
                return s.substring(1);
            }
            if (s.startsWith(REF)) {
                return resolve(s, ctx);
            }
            return s;
        }
        if (template instanceof Map<?, ?> m) {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), project(v, ctx)));
            return out;
        }
        if (template instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>(c.size());
            c.forEach(v -> out.add(project(v, ctx)));
            return out;
        }
        return template;
    }

    /** Projects a payload template; a non-map result is wrapped under {@code value} so the payload is always a map. */
    public static Map<String, Object> projectPayload(Object template, Context ctx) {
        if (template == null) {
            return Map.of();
        }
        Object projected = project(template, ctx);
        if (projected instanceof Map<?, ?> m) {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return Payloads.immutable(out);
        }
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("value", projected);
        return Payloads.immutable(out);
    }

    private static Object resolve(String ref, Context ctx) {
        if (ref.equals(PAYLOAD)) {
            return ctx.payload();
        }
        if (ref.startsWith(PAYLOAD + ".")) {
            return Payloads.path(ctx.payload(), ref.substring(PAYLOAD.length() + 1)).orElse(null);
        }
        return switch (ref) {
            case ENTITY_ID -> ctx.entity().id();
            case ENTITY_TYPE -> ctx.entity().type();
            case TENANT -> ctx.entity().tenantId();
            case ACTOR_ID -> ctx.actor().id();
            case EVENT_ID -> ctx.eventId();
            case STATE_FROM -> ctx.from();
            case STATE_TO -> ctx.to();
            default -> throw new IllegalArgumentException("unknown reference " + ref);
        };
    }
}
