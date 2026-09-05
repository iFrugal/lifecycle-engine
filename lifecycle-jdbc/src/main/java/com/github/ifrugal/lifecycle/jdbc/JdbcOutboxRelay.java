package com.github.ifrugal.lifecycle.jdbc;

import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.spi.Outbox;
import com.github.ifrugal.lifecycle.api.spi.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Repairs the gap between commit and publish (DD-07): a crash after {@code commit} but before {@code publish}
 * leaves rows with no {@code sent_at}, and this republishes them. At-least-once by construction — a duplicate is
 * deduped downstream by event id, which is exactly what the inbox is for.
 *
 * <p>Run it from a scheduler ({@code scheduleWithFixedDelay}). One run drains batch after batch until the outbox
 * is empty; a failure is logged and ends that run, leaving the rest for the next one.
 */
public final class JdbcOutboxRelay implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(JdbcOutboxRelay.class);

    private final Outbox outbox;
    private final Transport transport;
    private final int batchSize;

    public JdbcOutboxRelay(Outbox outbox, Transport transport, int batchSize) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.transport = Objects.requireNonNull(transport, "transport");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be > 0");
        }
        this.batchSize = batchSize;
    }

    @Override
    public void run() {
        long total = 0;
        try {
            int drained;
            do {
                drained = drainOnce();
                total += drained;
            } while (drained == batchSize);
        } catch (RuntimeException e) {
            log.error("outbox relay stopped after {} event(s): {}", total, e.toString(), e);
            return;
        }
        if (total > 0) {
            log.info("outbox relay published {} event(s)", total);
        }
    }

    /**
     * Publishes at most one batch and marks exactly what was published as sent.
     *
     * @return the number of events published; {@code 0} means the outbox is empty
     */
    public int drainOnce() {
        List<LifecycleEvent> batch = outbox.unsent(batchSize);
        if (batch.isEmpty()) {
            return 0;
        }
        List<String> published = new ArrayList<>(batch.size());
        try {
            for (LifecycleEvent event : batch) {
                transport.publish(event);
                published.add(event.eventId());
            }
        } finally {
            // Whatever did leave is marked sent even if a later one threw, so a failure republishes only the tail.
            outbox.markSent(published);
        }
        return published.size();
    }
}
