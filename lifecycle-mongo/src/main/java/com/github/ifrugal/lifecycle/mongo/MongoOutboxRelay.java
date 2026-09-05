package com.github.ifrugal.lifecycle.mongo;

import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.spi.Outbox;
import com.github.ifrugal.lifecycle.api.spi.Transport;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Republishes outbox rows that were committed but never confirmed sent (DD-07: "a crash between commit and
 * publish is repaired by the backend module's OutboxRelay"). Downstream consumers dedupe by event id, so
 * redelivering an already-delivered event is safe. Intended to be scheduled periodically (a
 * {@code ScheduledExecutorService}, a Spring {@code @Scheduled} method, a cron-style job, etc.); this class does
 * not schedule itself.
 */
public final class MongoOutboxRelay implements Runnable {

    private final Outbox outbox;
    private final Transport transport;
    private final int batchSize;

    public MongoOutboxRelay(Outbox outbox, Transport transport, int batchSize) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.transport = Objects.requireNonNull(transport, "transport");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
    }

    /** Publishes up to one batch of unsent events and marks them sent. @return how many were republished */
    public int drainOnce() {
        List<LifecycleEvent> events = outbox.unsent(batchSize);
        if (events.isEmpty()) {
            return 0;
        }
        List<String> sent = new ArrayList<>(events.size());
        for (LifecycleEvent event : events) {
            transport.publish(event);
            sent.add(event.eventId());
        }
        outbox.markSent(sent);
        return sent.size();
    }

    @Override
    public void run() {
        drainOnce();
    }
}
