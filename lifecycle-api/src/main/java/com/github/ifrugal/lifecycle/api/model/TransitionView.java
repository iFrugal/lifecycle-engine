package com.github.ifrugal.lifecycle.api.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only view of a compiled transition, for {@code available()} and for rendering. */
public record TransitionView(String id, String from, List<String> except, String on, Set<String> roles,
                             Map<String, Object> when, String guard, String to) {

    public TransitionView {
        except = except == null ? List.of() : List.copyOf(except);
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        when = Payloads.immutable(when);
    }
}
