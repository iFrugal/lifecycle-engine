package com.github.ifrugal.lifecycle.tasks;

import com.github.ifrugal.lifecycle.api.model.Causation;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTaskStoreTest {

    private final InMemoryTaskStore store = new InMemoryTaskStore();

    private static Task task(String id, String tenant, Set<String> assignTo, Instant createdAt) {
        return new Task(id, tenant, "approve-refund", assignTo, EntityRef.of("order", "o-1"), "evt-" + id,
                new Task.OnComplete("REFUND_DECIDED", EntityRef.of("order", "o-1")), Map.of(),
                Task.Status.OPEN, null, createdAt, createdAt, Causation.root("evt-" + id));
    }

    @Test
    void createIsIdempotentOnTaskId() {
        Task t = task("t1", null, Set.of("finance"), Instant.now());

        assertThat(store.create(t)).isTrue();
        assertThat(store.create(t)).isFalse();
        assertThat(store.find("t1")).contains(t);
    }

    @Test
    void casUnderConcurrencyHasExactlyOneWinner() throws InterruptedException {
        Task t = task("t2", null, Set.of("finance"), Instant.now());
        store.create(t);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                int idx = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    Task updated = t.with(Task.Status.CLAIMED, "actor-" + idx, Instant.now());
                    if (store.transition("t2", Task.Status.OPEN, updated)) {
                        winners.incrementAndGet();
                    }
                });
            }
            ready.await();
            go.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(winners.get()).isEqualTo(1);
        assertThat(store.find("t2")).get().extracting(Task::status).isEqualTo(Task.Status.CLAIMED);
    }

    @Test
    void openForOnlyReturnsOpenAndClaimedTasks() {
        Task open = task("open1", null, Set.of(), Instant.now());
        Task claimed = task("claimed1", null, Set.of(), Instant.now()).with(Task.Status.CLAIMED, "u1", Instant.now());
        Task completed = task("completed1", null, Set.of(), Instant.now()).with(Task.Status.COMPLETED, "u1", Instant.now());
        Task cancelled = task("cancelled1", null, Set.of(), Instant.now()).with(Task.Status.CANCELLED, null, Instant.now());
        store.create(open);
        store.create(claimed);
        store.create(completed);
        store.create(cancelled);

        List<Task> result = store.openFor(null, Set.of(), 10);

        assertThat(result).extracting(Task::taskId).containsExactlyInAnyOrder("open1", "claimed1");
    }

    @Test
    void openForFiltersByTenantAndRolesOrdersOldestFirstAndRespectsLimit() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Task a = task("a", "acme", Set.of("finance"), t0);
        Task b = task("b", "acme", Set.of("ops"), t0.plusSeconds(1));
        Task c = task("c", "other", Set.of("finance"), t0.plusSeconds(2));
        Task d = task("d", "acme", Set.of(), t0.plusSeconds(3)); // empty assignTo -> visible to everyone
        Task e = task("e", "acme", Set.of("finance"), t0.plusSeconds(4));
        store.create(a);
        store.create(b);
        store.create(c);
        store.create(d);
        store.create(e);

        assertThat(store.openFor("acme", Set.of("finance"), 10))
                .extracting(Task::taskId).containsExactly("a", "d", "e");

        assertThat(store.openFor("acme", Set.of("ops"), 10))
                .extracting(Task::taskId).containsExactly("b", "d");

        assertThat(store.openFor(null, Set.of("finance"), 10))
                .extracting(Task::taskId).containsExactly("a", "c", "d", "e");

        assertThat(store.openFor("acme", Set.of("finance"), 2))
                .extracting(Task::taskId).containsExactly("a", "d");
    }

    @Test
    void byEntityReturnsTasksCreatedByThatEntity() {
        Task forOrder1 = task("f1", null, Set.of(), Instant.now());
        Task forOrder2 = new Task("f2", null, "approve-refund", Set.of(), EntityRef.of("order", "o-2"), "evt-f2",
                new Task.OnComplete("REFUND_DECIDED", EntityRef.of("order", "o-2")), Map.of(),
                Task.Status.OPEN, null, Instant.now(), null, Causation.root("evt-f2"));
        store.create(forOrder1);
        store.create(forOrder2);

        assertThat(store.byEntity(EntityRef.of("order", "o-1"))).extracting(Task::taskId).containsExactly("f1");
        assertThat(store.byEntity(EntityRef.of("order", "o-2"))).extracting(Task::taskId).containsExactly("f2");
    }
}
