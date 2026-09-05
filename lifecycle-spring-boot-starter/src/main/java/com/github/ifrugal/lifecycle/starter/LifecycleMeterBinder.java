package com.github.ifrugal.lifecycle.starter;

import com.github.ifrugal.lifecycle.api.spi.Outbox;
import com.github.ifrugal.lifecycle.api.spi.StateStore;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * The meters that are not produced by handling an event (DD-13, bean 10):
 * {@code lifecycle.reloads{applied}} and {@code lifecycle.outbox.pending}. Outcome counts come from
 * {@link MeteredLifecycleEngine}, which is on the call path and can see them.
 *
 * <p>{@code lifecycle.outbox.pending} is a sampled gauge, not an exact backlog: it counts up to
 * {@link #OUTBOX_SAMPLE_LIMIT} unsent rows, so a genuinely stuck relay pegs the gauge at that ceiling instead of
 * dragging a full table scan into every scrape. Alert on "at the ceiling", not on the exact number.
 */
public class LifecycleMeterBinder implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(LifecycleMeterBinder.class);

    /** Most unsent events the pending gauge will count in one scrape. */
    public static final int OUTBOX_SAMPLE_LIMIT = 1000;

    public static final String RELOADS = "lifecycle.reloads";
    public static final String OUTBOX_PENDING = "lifecycle.outbox.pending";

    private final LifecycleRuleReloader reloader;
    private final StateStore store;

    public LifecycleMeterBinder(LifecycleRuleReloader reloader, StateStore store) {
        this.reloader = Objects.requireNonNull(reloader, "reloader");
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        FunctionCounter.builder(RELOADS, reloader, LifecycleRuleReloader::appliedReloads)
                .description("Rule loads that swapped a new snapshot in")
                .tag("applied", "true")
                .register(registry);
        FunctionCounter.builder(RELOADS, reloader, LifecycleRuleReloader::rejectedReloads)
                .description("Rule loads whose problems kept the previous snapshot live")
                .tag("applied", "false")
                .register(registry);

        store.outbox().ifPresent(outbox ->
                Gauge.builder(OUTBOX_PENDING, outbox, LifecycleMeterBinder::pending)
                        .description("Committed events not yet confirmed published, sampled up to " + OUTBOX_SAMPLE_LIMIT)
                        .strongReference(true)
                        .register(registry));
    }

    private static double pending(Outbox outbox) {
        try {
            return outbox.unsent(OUTBOX_SAMPLE_LIMIT).size();
        } catch (RuntimeException e) {
            log.debug("cannot sample the outbox: {}", e.toString());
            return Double.NaN;
        }
    }
}
