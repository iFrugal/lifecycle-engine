package com.github.ifrugal.lifecycle.core.acceptance;

import com.github.ifrugal.lifecycle.api.LifecycleEngine;
import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.AuditOutcome;
import com.github.ifrugal.lifecycle.api.model.Decision;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.Outcome;
import com.github.ifrugal.lifecycle.api.model.TransitionView;
import com.github.ifrugal.lifecycle.core.engine.DefaultLifecycleEngine;
import com.github.ifrugal.lifecycle.core.engine.Dispatcher;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryStateStore;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryTransport;
import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import com.github.ifrugal.lifecycle.core.registry.InMemoryDefinitionSource;
import com.github.ifrugal.lifecycle.core.rules.GuardRegistry;
import com.github.ifrugal.lifecycle.core.testdomain.RefundWindowOpen;
import com.github.ifrugal.lifecycle.core.testdomain.SampleRules;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** DD-02/DD-07: a caller-supplied {@code expectedVersion} is a UI-style optimistic precondition, never retried. */
class ExpectedVersionTest {

    /** Counts calls to {@code handle} while delegating everything else to a real engine. */
    private static final class CountingEngine implements LifecycleEngine {
        private final LifecycleEngine delegate;
        private final AtomicInteger handleCalls = new AtomicInteger();

        CountingEngine(LifecycleEngine delegate) {
            this.delegate = delegate;
        }

        @Override
        public Outcome handle(LifecycleEvent event) {
            handleCalls.incrementAndGet();
            return delegate.handle(event);
        }

        @Override
        public Decision evaluate(LifecycleEvent event) {
            return delegate.evaluate(event);
        }

        @Override
        public List<TransitionView> available(EntityRef ref, Actor actor) {
            return delegate.available(ref, actor);
        }
    }

    @Test
    void firstExpectedVersionZeroAppliesThenAReplayIsStaleAndConflictedWithoutRetry() {
        var store = new InMemoryStateStore();
        var guards = GuardRegistry.of(new RefundWindowOpen());
        var registry = new DefinitionRegistry(new InMemoryDefinitionSource(SampleRules.all()), guards, store);
        registry.reloadOrThrow();

        try (var transport = new InMemoryTransport()) {
            var realEngine = new DefaultLifecycleEngine(registry, store, transport, guards);
            EntityRef order = EntityRef.of("order", "o-ev");

            LifecycleEvent firstCancel = LifecycleEvent.builder()
                    .entity(order).action("CANCEL").actor(Actor.of("u1", "customer"))
                    .expectedVersion(0L)
                    .build();
            Outcome first = realEngine.handle(firstCancel);
            assertThat(first).isInstanceOf(Outcome.Applied.class);
            assertThat(store.find(order).map(r -> r.version())).contains(1L);

            CountingEngine counting = new CountingEngine(realEngine);
            LifecycleEvent staleEvent = LifecycleEvent.builder()
                    .entity(order).action("CANCEL").actor(Actor.of("u1", "customer"))
                    .expectedVersion(0L) // stale: the entity is already at version 1
                    .build();

            Outcome result = new Dispatcher(counting, 3).dispatch(staleEvent);

            assertThat(result).isInstanceOf(Outcome.Conflicted.class);
            Outcome.Conflicted conflicted = (Outcome.Conflicted) result;
            assertThat(conflicted.expected()).isEqualTo(0L);
            assertThat(conflicted.actual()).isEqualTo(1L);

            // not retried: exactly one handle() call for the stale, caller-pinned event
            assertThat(counting.handleCalls.get()).isEqualTo(1);

            // a detached CONFLICTED audit row exists; no state change
            assertThat(store.byEntity(order)).anySatisfy(a -> {
                assertThat(a.outcome()).isEqualTo(AuditOutcome.CONFLICTED);
                assertThat(a.eventId()).isEqualTo(staleEvent.eventId());
            });
            assertThat(store.find(order).map(r -> r.version())).contains(1L);
            assertThat(store.find(order).map(r -> r.state())).contains("CANCELLED");
        }
    }
}
