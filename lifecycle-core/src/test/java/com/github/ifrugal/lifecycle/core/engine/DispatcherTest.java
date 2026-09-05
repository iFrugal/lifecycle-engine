package com.github.ifrugal.lifecycle.core.engine;

import com.github.ifrugal.lifecycle.api.LifecycleEngine;
import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.Decision;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.Outcome;
import com.github.ifrugal.lifecycle.api.model.TransitionView;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryTransport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DD-07: the Dispatcher retries an unpinned {@link Outcome.Conflicted} from a fresh read, up to a limit. */
class DispatcherTest {

    /** Returns a scripted sequence of outcomes, repeating the last entry once exhausted. */
    private static final class ScriptedEngine implements LifecycleEngine {
        private final Deque<Outcome> script;
        private final AtomicInteger calls = new AtomicInteger();

        ScriptedEngine(Outcome... outcomes) {
            this.script = new ArrayDeque<>(List.of(outcomes));
        }

        @Override
        public Outcome handle(LifecycleEvent event) {
            calls.incrementAndGet();
            return script.size() > 1 ? script.poll() : script.peek();
        }

        @Override
        public Decision evaluate(LifecycleEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TransitionView> available(EntityRef ref, Actor actor) {
            throw new UnsupportedOperationException();
        }
    }

    private static LifecycleEvent event() {
        return LifecycleEvent.builder().entity(EntityRef.of("order", "o-1")).action("X").actor(Actor.of("u1")).build();
    }

    private static LifecycleEvent eventWithExpectedVersion(long v) {
        return LifecycleEvent.builder().entity(EntityRef.of("order", "o-1")).action("X").actor(Actor.of("u1")).expectedVersion(v).build();
    }

    @Test
    void conflictedTwiceThenAppliedIsRetriedAndSucceeds() {
        ScriptedEngine engine = new ScriptedEngine(
                new Outcome.Conflicted(0, 1),
                new Outcome.Conflicted(0, 2),
                new Outcome.Applied("t1", "A", "B", 3, List.of()));

        Outcome result = new Dispatcher(engine, 3).dispatch(event());

        assertThat(result).isInstanceOf(Outcome.Applied.class);
        assertThat(engine.calls.get()).isEqualTo(3); // N=2 conflicts + 1 final call = N+1
    }

    @Test
    void alwaysConflictedThrowsRedeliveryRequestedAfterRetriesExhausted() {
        ScriptedEngine engine = new ScriptedEngine(new Outcome.Conflicted(0, 1));

        assertThatThrownBy(() -> new Dispatcher(engine, 3).dispatch(event()))
                .isInstanceOf(RedeliveryRequested.class);
        assertThat(engine.calls.get()).isEqualTo(4); // 1 initial + 3 retries
    }

    @Test
    void conflictedWithExpectedVersionSetIsReturnedImmediatelyWithoutRetry() {
        ScriptedEngine engine = new ScriptedEngine(new Outcome.Conflicted(0, 5));

        Outcome result = new Dispatcher(engine, 3).dispatch(eventWithExpectedVersion(0L));

        assertThat(result).isInstanceOf(Outcome.Conflicted.class);
        assertThat(engine.calls.get()).isEqualTo(1);
    }

    @Test
    void inMemoryTransportRedeliversAThrowingSubscriberAndDeadLettersAfterMaxAttempts() {
        try (var transport = new InMemoryTransport(5, Duration.ofMillis(1))) {
            transport.subscribe(e -> { throw new RuntimeException("always fails"); });

            LifecycleEvent signal = event();
            transport.publish(signal);

            assertThat(transport.awaitIdle(Duration.ofSeconds(5))).isTrue();
            assertThat(transport.deadLetters()).hasSize(1);
            assertThat(transport.deadLetters().get(0).eventId()).isEqualTo(signal.eventId());
        }
    }
}
