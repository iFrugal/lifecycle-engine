package com.github.ifrugal.lifecycle.starter;

import com.github.ifrugal.lifecycle.api.spi.DefinitionSource;
import com.github.ifrugal.lifecycle.api.spi.Transport;
import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Owns the rule snapshot lifecycle for the application context (DD-13, bean 4): the first load on
 * {@link ApplicationReadyEvent}, the optional poller, and the reload the actuator endpoint calls.
 *
 * <p>Polling is a plain daemon thread rather than {@code @Scheduled} so the starter never forces
 * {@code @EnableScheduling} on an application, and it reloads only when the source fingerprint has moved, so a
 * short poll interval against a database costs one cheap query.
 */
public class LifecycleRuleReloader implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(LifecycleRuleReloader.class);

    private final DefinitionRegistry registry;
    private final DefinitionSource source;
    private final Transport transport;
    private final Duration poll;
    private final boolean failFast;

    private final AtomicReference<DefinitionRegistry.ReloadResult> lastReload = new AtomicReference<>();
    private final AtomicLong appliedReloads = new AtomicLong();
    private final AtomicLong rejectedReloads = new AtomicLong();
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile Thread poller;
    private volatile boolean running = true;

    public LifecycleRuleReloader(DefinitionRegistry registry, DefinitionSource source, Transport transport,
                                 Duration poll, boolean failFast) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.source = Objects.requireNonNull(source, "source");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.poll = poll == null ? Duration.ZERO : poll;
        this.failFast = failFast;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        if (failFast) {
            record(registry.reloadOrThrow());
        } else {
            DefinitionRegistry.ReloadResult result = record(registry.reload());
            if (!result.applied()) {
                log.error("initial rule load rejected and fail-fast is off; the engine has no snapshot and every "
                        + "event will be refused until a reload succeeds");
            }
        }
        checkDelaySupport();
        startPolling();
    }

    /** Reload now, whatever the fingerprint says. Used by the actuator endpoint and callable by an application. */
    public DefinitionRegistry.ReloadResult reload() {
        DefinitionRegistry.ReloadResult result = record(registry.reload());
        if (result.applied()) {
            checkDelaySupport();
        }
        return result;
    }

    /** The outcome of the most recent load attempt, or empty before the first one. */
    public Optional<DefinitionRegistry.ReloadResult> lastReload() {
        return Optional.ofNullable(lastReload.get());
    }

    public DefinitionRegistry registry() {
        return registry;
    }

    /** Number of loads that swapped a new snapshot in. Read by the Micrometer binder. */
    public long appliedReloads() {
        return appliedReloads.get();
    }

    /** Number of loads whose problems kept the previous snapshot live. Read by the Micrometer binder. */
    public long rejectedReloads() {
        return rejectedReloads.get();
    }

    private DefinitionRegistry.ReloadResult record(DefinitionRegistry.ReloadResult result) {
        lastReload.set(result);
        (result.applied() ? appliedReloads : rejectedReloads).incrementAndGet();
        return result;
    }

    /**
     * The same guard {@code DefaultLifecycleEngine} applies in its constructor (DD-09). It cannot fire there
     * here, because the engine bean is built before the first load, so it is applied at load time instead.
     */
    private void checkDelaySupport() {
        if (registry.isLoaded() && registry.requiresDelay() && !transport.supportsDelay()) {
            throw new IllegalStateException("loaded rules declare `after` (timers) but the configured transport "
                    + transport.getClass().getSimpleName() + " does not support delayed delivery");
        }
    }

    private void startPolling() {
        if (poll.isZero() || poll.isNegative()) {
            log.debug("rule polling disabled; reload via the lifecyclerules endpoint or LifecycleRuleReloader.reload()");
            return;
        }
        Thread t = new Thread(this::pollLoop, "lifecycle-rule-poller");
        t.setDaemon(true);
        poller = t;
        t.start();
        log.info("polling rule sources every {}", poll);
    }

    private void pollLoop() {
        String seen = registry.isLoaded() ? registry.snapshot().version() : null;
        while (running) {
            LockSupport.parkNanos(poll.toNanos());
            if (!running) {
                return;
            }
            try {
                String fingerprint = source.fingerprint();
                if (Objects.equals(fingerprint, seen)) {
                    continue;
                }
                seen = fingerprint;
                DefinitionRegistry.ReloadResult result = reload();
                log.info("rule source changed to {}: reload {}", fingerprint, result.applied() ? "applied" : "rejected");
            } catch (RuntimeException e) {
                log.error("rule poll failed: {}", e.toString(), e);
            }
        }
    }

    @Override
    public void destroy() {
        running = false;
        Thread t = poller;
        if (t != null) {
            LockSupport.unpark(t);
        }
    }
}
