package com.github.ifrugal.lifecycle.api.spi;

import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;

import java.util.Collection;
import java.util.List;

/** Events committed but not yet confirmed published. The engine marks sent after publishing; a relay drains the rest. */
public interface Outbox {

    List<LifecycleEvent> unsent(int limit);

    void markSent(Collection<String> eventIds);
}
