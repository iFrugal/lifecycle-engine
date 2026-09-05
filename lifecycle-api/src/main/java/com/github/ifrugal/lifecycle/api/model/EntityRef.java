package com.github.ifrugal.lifecycle.api.model;

import java.util.Objects;

/**
 * Anything with a lifecycle. {@code type} selects the machine; {@code tenantId} is optional and opaque to the
 * engine (it selects a tenant overlay of the machine, if one exists).
 */
public record EntityRef(String tenantId, String type, String id) {

    public EntityRef {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        if (tenantId != null && tenantId.isBlank()) {
            tenantId = null;
        }
    }

    public static EntityRef of(String type, String id) {
        return new EntityRef(null, type, id);
    }

    /** Stable string key, suitable for maps and partitioning. */
    public String key() {
        return (tenantId == null ? "" : tenantId) + "/" + type + "/" + id;
    }
}
