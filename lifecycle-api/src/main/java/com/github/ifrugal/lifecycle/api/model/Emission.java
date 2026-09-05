package com.github.ifrugal.lifecycle.api.model;

import com.github.ifrugal.lifecycle.api.rules.Dispatch;

import java.util.Objects;

/** An event a transition would emit, with how it is to be dispatched (DD-09). */
public record Emission(LifecycleEvent event, Dispatch dispatch) {
    public Emission {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(dispatch, "dispatch");
    }
}
