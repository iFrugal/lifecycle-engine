package com.github.ifrugal.lifecycle.core.engine;

import com.github.ifrugal.lifecycle.api.model.Actor;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * @param engineActor actor stamped on every emitted event; rules that consume engine-raised signals list its role
 * @param clock       time source for emitted events and audit rows
 * @param auditIds    generator for audit ids
 */
public record EngineConfig(Actor engineActor, Clock clock, Supplier<String> auditIds) {

    public static final String ENGINE_ACTOR_ID = "lifecycle-engine";

    public static EngineConfig defaults() {
        return new EngineConfig(
                new Actor(ENGINE_ACTOR_ID, Set.of(ENGINE_ACTOR_ID), Actor.Kind.ENGINE),
                Clock.systemUTC(),
                () -> UUID.randomUUID().toString());
    }

    public EngineConfig withClock(Clock c) {
        return new EngineConfig(engineActor, c, auditIds);
    }
}
