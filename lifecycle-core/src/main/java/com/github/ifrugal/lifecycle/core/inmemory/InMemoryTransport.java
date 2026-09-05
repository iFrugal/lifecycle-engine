package com.github.ifrugal.lifecycle.core.inmemory;

import com.github.ifrugal.lifecycle.api.model.EventKind;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.spi.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Reference transport: one FIFO queue on one thread, delayed delivery honoured, a throwing subscriber is
 * redelivered up to {@code maxDeliveries} then dead-lettered. Notifications are collected for inspection and
 * handed to {@link #onNotification} listeners. Subscribe before publishing.
 */
public final class InMemoryTransport implements Transport, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(InMemoryTransport.class);

    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lifecycle-inmemory-transport");
        t.setDaemon(true);
        return t;
    });
    private final List<Consumer<LifecycleEvent>> inbound = new CopyOnWriteArrayList<>();
    private final List<Consumer<LifecycleEvent>> notificationListeners = new CopyOnWriteArrayList<>();
    private final List<LifecycleEvent> notifications = Collections.synchronizedList(new ArrayList<>());
    private final List<LifecycleEvent> deadLetters = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger pending = new AtomicInteger();
    private final int maxDeliveries;
    private final Duration redeliveryDelay;

    public InMemoryTransport() {
        this(5, Duration.ofMillis(10));
    }

    public InMemoryTransport(int maxDeliveries, Duration redeliveryDelay) {
        this.maxDeliveries = maxDeliveries;
        this.redeliveryDelay = redeliveryDelay;
    }

    @Override
    public void publish(LifecycleEvent event) {
        if (event.kind() == EventKind.NOTIFICATION) {
            notifications.add(event);
            notificationListeners.forEach(l -> l.accept(event));
            return;
        }
        pending.incrementAndGet();
        long delayMs = event.deliverAt() == null ? 0L : Math.max(0L, Duration.between(Instant.now(), event.deliverAt()).toMillis());
        exec.schedule(() -> deliver(event, 1), delayMs, TimeUnit.MILLISECONDS);
    }

    private void deliver(LifecycleEvent event, int attempt) {
        try {
            if (inbound.isEmpty()) {
                throw new IllegalStateException("no subscriber");
            }
            for (Consumer<LifecycleEvent> c : inbound) {
                c.accept(event);
            }
            pending.decrementAndGet();
        } catch (RuntimeException ex) {
            if (attempt < maxDeliveries) {
                exec.schedule(() -> deliver(event, attempt + 1), redeliveryDelay.toMillis(), TimeUnit.MILLISECONDS);
            } else {
                log.error("dead-lettering {} on {} after {} deliveries: {}", event.action(), event.entity().key(), attempt, ex.toString());
                deadLetters.add(event);
                pending.decrementAndGet();
            }
        }
    }

    @Override
    public void subscribe(Consumer<LifecycleEvent> handler) {
        inbound.add(handler);
    }

    public void onNotification(Consumer<LifecycleEvent> listener) {
        notificationListeners.add(listener);
    }

    @Override
    public boolean supportsDelay() {
        return true;
    }

    /** Waits until every published signal (including delayed and redelivered ones) has been handled or dead-lettered. */
    public boolean awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (pending.get() > 0) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    public List<LifecycleEvent> notifications() {
        return List.copyOf(notifications);
    }

    public List<LifecycleEvent> deadLetters() {
        return List.copyOf(deadLetters);
    }

    public int pending() {
        return pending.get();
    }

    @Override
    public void close() {
        exec.shutdownNow();
    }
}
