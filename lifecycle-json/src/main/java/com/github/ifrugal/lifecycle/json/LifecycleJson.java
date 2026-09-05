package com.github.ifrugal.lifecycle.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.ifrugal.lifecycle.api.model.AuditRecord;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;

import java.util.Map;

/**
 * One codec for the wire and the outbox. Forward compatible: unknown fields are ignored on read so an older
 * consumer can read a newer producer's event. Instants are ISO-8601 strings. The api records are serialised by
 * their canonical constructors; no annotations leak into lifecycle-api.
 */
public final class LifecycleJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    private LifecycleJson() {}

    /** The shared, immutable-configuration mapper, for adapters that need to serialise other things the same way. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String write(LifecycleEvent event) {
        return write((Object) event);
    }

    public static LifecycleEvent readEvent(String json) {
        return read(json, LifecycleEvent.class);
    }

    public static String write(AuditRecord record) {
        return write((Object) record);
    }

    public static AuditRecord readAudit(String json) {
        return read(json, AuditRecord.class);
    }

    public static String writeMap(Map<String, ?> map) {
        return write((Object) map);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readMap(String json) {
        return read(json, Map.class);
    }

    public static String writeAny(Object value) {
        return write(value);
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new JsonCodecException("cannot read " + type.getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
    }

    private static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new JsonCodecException("cannot write " + value.getClass().getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
    }

    public static final class JsonCodecException extends RuntimeException {
        public JsonCodecException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
