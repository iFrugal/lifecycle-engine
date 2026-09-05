package com.github.ifrugal.lifecycle.api.spi;

import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;

import java.util.function.Consumer;

/**
 * The transport seam (R4). The engine's whole knowledge of the outside world. Signals come back through
 * {@link #subscribe}; a subscriber that throws is asking for redelivery.
 */
public interface Transport {

    /** Route by {@code event.kind()}: signals back toward an engine, notifications toward effect consumers. */
    void publish(LifecycleEvent event);

    /** Register the inbound handler for signals. Typically a {@code Dispatcher}. */
    void subscribe(Consumer<LifecycleEvent> inbound);

    /** Whether {@code deliverAt} is honoured. Checked at startup when any loaded rule declares {@code after}. */
    boolean supportsDelay();
}
