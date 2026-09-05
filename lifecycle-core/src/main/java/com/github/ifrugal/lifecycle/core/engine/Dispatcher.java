package com.github.ifrugal.lifecycle.core.engine;

import com.github.ifrugal.lifecycle.api.LifecycleEngine;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.Outcome;
import com.github.ifrugal.lifecycle.api.spi.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Fronts transport delivery. A {@link Outcome.Conflicted} is retried from a fresh read up to {@code conflictRetries}
 * times, then handed back to the transport via {@link RedeliveryRequested}. A caller-pinned expected version is
 * never retried (DD-07).
 */
public final class Dispatcher implements Consumer<LifecycleEvent> {

    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);

    public static final int DEFAULT_CONFLICT_RETRIES = 3;

    private final LifecycleEngine engine;
    private final int conflictRetries;

    public Dispatcher(LifecycleEngine engine) {
        this(engine, DEFAULT_CONFLICT_RETRIES);
    }

    public Dispatcher(LifecycleEngine engine, int conflictRetries) {
        this.engine = engine;
        this.conflictRetries = conflictRetries;
    }

    public Outcome dispatch(LifecycleEvent event) {
        Outcome outcome = engine.handle(event);
        int attempt = 0;
        while (outcome instanceof Outcome.Conflicted && event.expectedVersion() == null && attempt < conflictRetries) {
            attempt++;
            log.debug("conflict on {} ({}); retry {}/{}", event.entity().key(), event.action(), attempt, conflictRetries);
            outcome = engine.handle(event);
        }
        if (outcome instanceof Outcome.Conflicted c && event.expectedVersion() == null) {
            throw new RedeliveryRequested("still conflicted after " + conflictRetries + " retries: expected " + c.expected() + ", actual " + c.actual(), outcome);
        }
        return outcome;
    }

    @Override
    public void accept(LifecycleEvent event) {
        dispatch(event);
    }

    public Dispatcher attachTo(Transport transport) {
        transport.subscribe(this);
        return this;
    }
}
