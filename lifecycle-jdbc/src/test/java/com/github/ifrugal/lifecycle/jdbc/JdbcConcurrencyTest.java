package com.github.ifrugal.lifecycle.jdbc;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.AuditOutcome;
import com.github.ifrugal.lifecycle.api.model.AuditRecord;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.Outcome;
import com.github.ifrugal.lifecycle.api.model.StateRecord;
import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;
import com.github.ifrugal.lifecycle.api.rules.TransitionDocument;
import com.github.ifrugal.lifecycle.core.engine.DefaultLifecycleEngine;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryTransport;
import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import com.github.ifrugal.lifecycle.core.registry.InMemoryDefinitionSource;
import com.github.ifrugal.lifecycle.core.rules.GuardRegistry;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H7 on a real database. Eight threads hammer one entity with a toggle that always matches, so every event
 * either applies or loses the optimistic version race and nothing may be silently swallowed: applied outcomes
 * must equal the final version exactly, every applied event must have its own audit row, and every conflict
 * must be visible as a detached CONFLICTED row. The assertions are deliberately the same as
 * {@code H7ConcurrencyTest}'s over the in-memory store — one contract, two implementations.
 */
abstract class JdbcConcurrencyTest {

    private static final String TYPE = "toggle";
    private static final int THREADS = 8;
    private static final int EVENTS_PER_THREAD = 500;
    private static final int TOTAL = THREADS * EVENTS_PER_THREAD;

    protected abstract DataSource dataSource();

    protected abstract Dialect dialect();

    /** A <-> B on TOGGLE. No roles, no when, no guard: every event matches, so only the version can refuse it. */
    private static RuleSetDocument toggle() {
        return new RuleSetDocument(null, TYPE, "A", null,
                List.of("A", "B"),
                List.of(
                        TransitionDocument.of("toggle.ab", "A", "TOGGLE", "B"),
                        TransitionDocument.of("toggle.ba", "B", "TOGGLE", "A")));
    }

    @Test
    void contentionIsDetectedNeverLost() throws Exception {
        DataSource ds = dataSource();
        SchemaInstaller.install(ds, dialect());
        TestDatabases.truncateAll(ds);

        JdbcStateStore store = new JdbcStateStore(ds, dialect());
        GuardRegistry guards = GuardRegistry.empty();
        DefinitionRegistry registry = new DefinitionRegistry(new InMemoryDefinitionSource(toggle()), guards, store);
        registry.reloadOrThrow();

        try (InMemoryTransport transport = new InMemoryTransport()) {
            transport.subscribe(e -> { /* nothing is emitted by this machine */ });
            DefaultLifecycleEngine engine = new DefaultLifecycleEngine(registry, store, transport, guards);
            EntityRef entity = new EntityRef(null, TYPE, "t-1");

            var outcomes = new ConcurrentLinkedQueue<Outcome>();
            var failures = new ConcurrentLinkedQueue<Throwable>();
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(THREADS);

            for (int t = 0; t < THREADS; t++) {
                final int threadNo = t;
                Thread worker = new Thread(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < EVENTS_PER_THREAD; i++) {
                            outcomes.add(engine.handle(LifecycleEvent.builder()
                                    .eventId("e-" + threadNo + "-" + i)
                                    .entity(entity)
                                    .action("TOGGLE")
                                    .actor(Actor.of("u" + threadNo))
                                    .build()));
                        }
                    } catch (Throwable ex) {
                        failures.add(ex);
                    } finally {
                        done.countDown();
                    }
                }, "jdbc-h7-worker-" + t);
                worker.start();
            }

            start.countDown();
            assertThat(done.await(300, TimeUnit.SECONDS)).as("all workers finished").isTrue();

            assertThat(failures).as("no worker threw").isEmpty();
            assertThat(outcomes).as("every event produced an outcome").hasSize(TOTAL);

            long applied = outcomes.stream().filter(Outcome.Applied.class::isInstance).count();
            long conflicted = outcomes.stream().filter(Outcome.Conflicted.class::isInstance).count();
            long refused = outcomes.stream().filter(Outcome.Refused.class::isInstance).count();
            long duplicate = outcomes.stream().filter(Outcome.Duplicate.class::isInstance).count();

            assertThat(refused).as("this machine can never refuse on rules").isZero();
            assertThat(duplicate).as("every event id is unique").isZero();
            assertThat(applied + conflicted).isEqualTo(TOTAL);
            assertThat(conflicted).as("eight threads on one entity must actually contend").isPositive();
            assertThat(applied).isPositive();

            StateRecord finalRecord = store.find(entity).orElseThrow();
            assertThat(finalRecord.version()).as("final version == number of applied outcomes (DD-07)").isEqualTo(applied);
            assertThat(finalRecord.state()).isEqualTo(applied % 2 == 0 ? "A" : "B");

            List<AuditRecord> audit = store.byEntity(entity);
            List<AuditRecord> appliedRows = audit.stream().filter(a -> a.outcome() == AuditOutcome.APPLIED).toList();
            List<AuditRecord> conflictRows = audit.stream().filter(a -> a.outcome() == AuditOutcome.CONFLICTED).toList();

            assertThat(appliedRows).as("one APPLIED row per applied outcome").hasSize((int) applied);
            Set<String> appliedEventIds = appliedRows.stream().map(AuditRecord::eventId).collect(Collectors.toSet());
            assertThat(appliedEventIds).as("APPLIED rows carry unique event ids").hasSize((int) applied);

            assertThat(conflictRows).as("one detached CONFLICTED row per conflict (R12)").hasSize((int) conflicted);
            assertThat(conflictRows).allSatisfy(a -> {
                assertThat(a.toState()).isNull();
                assertThat(a.transitionId()).isNull();
                assertThat(a.detail()).isNotBlank();
                assertThat(a.entity()).isEqualTo(entity);
            });

            assertThat(audit).as("nothing else was written").hasSize((int) (applied + conflicted));
            assertThat(TestDatabases.count(ds, "lifecycle_inbox"))
                    .as("only the committed attempts reached the inbox").isEqualTo(applied);
            assertThat(appliedRows).extracting(AuditRecord::toState).allMatch(s -> s.equals("A") || s.equals("B"));
            assertThat(transport.awaitIdle(Duration.ofSeconds(5))).isTrue();
            assertThat(transport.deadLetters()).isEmpty();
        }
    }
}
