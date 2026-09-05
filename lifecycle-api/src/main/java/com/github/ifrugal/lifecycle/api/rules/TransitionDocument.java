package com.github.ifrugal.lifecycle.api.rules;

import com.github.ifrugal.lifecycle.api.model.Payloads;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An edge as declared. Every optional field is normalised to an empty collection or null on construction, so
 * optionality in the schema is optionality at runtime (H4). {@code disabled} is only meaningful in overlays.
 */
public record TransitionDocument(
        String id,
        String from,
        List<String> except,
        String on,
        Set<String> roles,
        Map<String, Object> when,
        String guard,
        String to,
        List<EmitDocument> emit,
        TaskDocument task,
        boolean disabled) {

    public TransitionDocument {
        except = except == null ? List.of() : List.copyOf(except);
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        when = Payloads.immutable(when);
        emit = emit == null ? List.of() : List.copyOf(emit);
    }

    /** The minimal edge: from, on, to. */
    public static TransitionDocument of(String id, String from, String on, String to) {
        return new TransitionDocument(id, from, null, on, null, null, null, to, null, null, false);
    }

    public static TransitionDocument disabled(String id) {
        return new TransitionDocument(id, null, null, null, null, null, null, null, null, null, true);
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static final class Builder {
        private final String id;
        private String from;
        private List<String> except;
        private String on;
        private Set<String> roles;
        private Map<String, Object> when;
        private String guard;
        private String to;
        private List<EmitDocument> emit;
        private TaskDocument task;

        private Builder(String id) { this.id = id; }

        public Builder from(String v) { this.from = v; return this; }
        public Builder except(String... v) { this.except = List.of(v); return this; }
        public Builder on(String v) { this.on = v; return this; }
        public Builder roles(String... v) { this.roles = Set.of(v); return this; }
        public Builder when(Map<String, Object> v) { this.when = v; return this; }
        public Builder guard(String v) { this.guard = v; return this; }
        public Builder to(String v) { this.to = v; return this; }
        public Builder emit(EmitDocument... v) { this.emit = List.of(v); return this; }
        public Builder task(TaskDocument v) { this.task = v; return this; }

        public TransitionDocument build() {
            return new TransitionDocument(id, from, except, on, roles, when, guard, to, emit, task, false);
        }
    }
}
