package com.github.ifrugal.lifecycle.core.engine;

import com.github.ifrugal.lifecycle.api.model.Outcome;

/** Thrown by the {@link Dispatcher} to ask the transport to redeliver with its own backoff. */
public class RedeliveryRequested extends RuntimeException {

    private final transient Outcome lastOutcome;

    public RedeliveryRequested(String message, Outcome lastOutcome) {
        super(message);
        this.lastOutcome = lastOutcome;
    }

    public Outcome lastOutcome() {
        return lastOutcome;
    }
}
