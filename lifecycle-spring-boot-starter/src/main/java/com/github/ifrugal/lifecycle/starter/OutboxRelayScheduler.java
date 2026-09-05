package com.github.ifrugal.lifecycle.starter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs a backend module's outbox relay on one daemon thread (DD-13, bean 8). {@code scheduleWithFixedDelay}, not
 * {@code atFixedRate}: a slow drain must not queue another one behind it.
 *
 * <p>A relay {@code Runnable} from either backend already swallows and logs its own failures, so the task can
 * never die and silently stop the schedule.
 */
public class OutboxRelayScheduler implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final ScheduledExecutorService executor;
    private final Runnable relay;

    public OutboxRelayScheduler(Runnable relay, Duration period, String threadName) {
        this.relay = Objects.requireNonNull(relay, "relay");
        Objects.requireNonNull(period, "period");
        if (period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("outbox relay period must be positive, was " + period);
        }
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
        long millis = Math.max(1L, period.toMillis());
        executor.scheduleWithFixedDelay(this::runQuietly, millis, millis, TimeUnit.MILLISECONDS);
        log.info("outbox relay scheduled every {}", period);
    }

    /** Drains once on the calling thread. Useful in tests that do not want to wait for the next tick. */
    public void drainNow() {
        relay.run();
    }

    private void runQuietly() {
        try {
            relay.run();
        } catch (RuntimeException e) {
            log.error("outbox relay run failed: {}", e.toString(), e);
        }
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
