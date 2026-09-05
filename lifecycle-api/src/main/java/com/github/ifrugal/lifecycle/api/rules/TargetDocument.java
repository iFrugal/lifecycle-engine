package com.github.ifrugal.lifecycle.api.rules;

/**
 * Where an emitted signal goes. {@code self} targets the emitting entity; otherwise {@code type} plus an
 * {@code id} that is a literal or a {@code $}-reference resolved against the consumed event.
 */
public record TargetDocument(boolean self, String type, Object id) {

    public static TargetDocument toSelf() {
        return new TargetDocument(true, null, null);
    }

    public static TargetDocument of(String type, Object id) {
        return new TargetDocument(false, type, id);
    }
}
