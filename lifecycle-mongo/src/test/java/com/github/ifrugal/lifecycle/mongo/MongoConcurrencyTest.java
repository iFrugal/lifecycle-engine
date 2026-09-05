package com.github.ifrugal.lifecycle.mongo;

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
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.github.ifrugal.lifecycle.mongo.TestSupport.id;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * DD-07/R12 over MongoDB, transactional mode: eight threads hammer one entity with a toggle transition that
 * always matches, so every event either applies or loses the optimistic version race — the same shape as
 * lifecycle-core's {@code H7ConcurrencyTest}, here proving {@link MongoStateStore}'s transaction-retry loop
 * (write conflicts on the one contended document surface as MongoDB TransientTransactionErrors, which
 * {@code commit} retries internally) never loses or duplicates an update.
 */
@Testcontainers(disabledWithoutDocker = true)
class MongoConcurrencyTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

    private static final String TYPE = "toggle";
    private static final int THREADS = 8;
    private static final int EVENTS_PER_THREAD = 20;
    private static final int TOTAL = THREADS * EVENTS_PER_THREAD;

    private MongoClient client;
    private MongoDatabase database;

    @BeforeEach
    void setUp() {
        client = MongoClients.create(MONGO.getConnectionString());
        database = client.getDatabase("test_" + id().replace("-", ""));
        MongoSchema.ensureIndexes(database);
    }

    @AfterEach
    void tearDown() {
        database.drop();
        client.close();
    }

    private static RuleSetDocument toggle() {
        return new RuleSetDocument(null, TYPE, "A", null,
                List.of("A", "B"),
                List.of(
                        TransitionDocument.of("toggle.ab", "A", "TOGGLE", "B"),
                        TransitionDocument.of("toggle.ba", "B", "TOGGLE", "A")));
    }

    @Test
    void contentionIsDetectedNeverLost() throws Exception {
        MongoStateStore store = new MongoStateStore(client, database, Duration.ofDays(7), true);
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
                }, "mongo-concurrency-worker-" + t);
                worker.start();
            }

            long t0 = System.nanoTime();
            Thread watcher = new Thread(() -> {
                while (done.getCount() > 0) {
                    System.out.println("[watch] outcomes=" + outcomes.size() + " elapsedMs=" + (System.nanoTime() - t0) / 1_000_000);
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
            });
            watcher.setDaemon(true);
            watcher.start();

            start.countDown();
            // Real MongoDB transactions (network round trips + journal fsync per commit) are far slower than
            // InMemoryStateStore's synchronized monitor, and eight-way contention on one document multiplies
            // whole-transaction retries; this budget is generous on purpose.
            assertThat(done.await(480, TimeUnit.SECONDS)).as("all workers finished").isTrue();
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

            List<AuditRecord> audit = store.byEntity(entity);
            List<AuditRecord> appliedRows = audit.stream().filter(a -> a.outcome() == AuditOutcome.APPLIED).toList();
            assertThat(appliedRows).as("one APPLIED row per applied outcome").hasSize((int) applied);
            Set<String> appliedEventIds = appliedRows.stream().map(AuditRecord::eventId).collect(Collectors.toSet());
            assertThat(appliedEventIds).as("APPLIED rows carry unique event ids").hasSize((int) applied);

            assertThat(transport.awaitIdle(Duration.ofSeconds(5))).isTrue();
            assertThat(transport.deadLetters()).isEmpty();
        }
    }
}
