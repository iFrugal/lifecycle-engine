package com.github.ifrugal.lifecycle.api.model;

import java.util.Objects;
import java.util.Set;

/**
 * Who or what is acting. Roles arrive on the event: the engine performs no lookups. An empty role set is a
 * legal actor (H4).
 */
public record Actor(String id, Set<String> roles, Kind kind) {

    public enum Kind { HUMAN, SERVICE, ENGINE, SCHEDULER }

    public Actor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public static Actor of(String id, String... roles) {
        return new Actor(id, Set.of(roles), Kind.HUMAN);
    }

    public static Actor service(String id, String... roles) {
        return new Actor(id, Set.of(roles), Kind.SERVICE);
    }
}
