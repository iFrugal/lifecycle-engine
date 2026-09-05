package com.github.ifrugal.lifecycle.starter;

import com.github.ifrugal.lifecycle.api.LifecycleEngine;
import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.Decision;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.Outcome;
import com.github.ifrugal.lifecycle.api.model.TransitionView;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.List;
import java.util.Objects;

/**
 * Counts what the engine decided, without the engine knowing that anything is counting (DD-13, bean 10).
 *
 * <p>Only {@link #handle} is metered: {@link #evaluate} and {@link #available} are dry runs that an application
 * is expected to call speculatively and often (H3), and counting them would drown the real outcomes.
 *
 * <p>One counter, {@code lifecycle.outcomes}, tagged {@code outcome} (the sealed {@code Outcome} member) and
 * {@code reason} (the {@code RefusalReason} for a refusal, {@code none} otherwise). Tag values are bounded by
 * two enums and a fixed set of four outcomes, so the cardinality cannot run away.
 */
public class MeteredLifecycleEngine implements LifecycleEngine {

    /** Counter name. */
    public static final String OUTCOMES = "lifecycle.outcomes";

    private static final String NO_REASON = "none";

    private final LifecycleEngine delegate;
    private final MeterRegistry meters;

    public MeteredLifecycleEngine(LifecycleEngine delegate, MeterRegistry meters) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.meters = Objects.requireNonNull(meters, "meters");
    }

    /** The engine underneath, typically the {@code DefaultLifecycleEngine}. */
    public LifecycleEngine delegate() {
        return delegate;
    }

    @Override
    public Outcome handle(LifecycleEvent event) {
        Outcome outcome = delegate.handle(event);
        count(outcome);
        return outcome;
    }

    @Override
    public Decision evaluate(LifecycleEvent event) {
        return delegate.evaluate(event);
    }

    @Override
    public List<TransitionView> available(EntityRef ref, Actor actor) {
        return delegate.available(ref, actor);
    }

    private void count(Outcome outcome) {
        String name;
        String reason = NO_REASON;
        switch (outcome) {
            case Outcome.Applied ignored -> name = "APPLIED";
            case Outcome.Refused r -> {
                name = "REFUSED";
                reason = r.reason().name();
            }
            case Outcome.Conflicted ignored -> name = "CONFLICTED";
            case Outcome.Duplicate ignored -> name = "DUPLICATE";
        }
        Counter.builder(OUTCOMES)
                .description("Engine outcomes by kind, and refusal reason where there is one")
                .tag("outcome", name)
                .tag("reason", reason)
                .register(meters)
                .increment();
    }
}
