package com.github.ifrugal.lifecycle.api.guard;

import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.StateRecord;

/** Everything a named guard may look at. Immutable; a guard cannot reach the store or the transport. */
public record GuardContext(StateRecord current, LifecycleEvent event) {}
