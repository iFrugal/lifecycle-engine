package com.github.ifrugal.lifecycle.jdbc;

import com.github.ifrugal.lifecycle.api.model.Causation;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.tasks.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DD-10 over a table: create is idempotent on the task id, and {@code transition} is a compare-and-set so two
 * claimants cannot both win however many processes are racing.
 */
class JdbcTaskStoreTest {

    private static final DataSource DS = TestDatabases.h2();
    private static final Instant T0 = Instant.parse("2026-01-01T09:00:00Z");

    private JdbcTaskStore store;

    @BeforeEach
    void setUp() {
        SchemaInstaller.install(DS, Dialect.H2);
        TestDatabases.truncateAll(DS);
        store = new JdbcTaskStore(DS);
    }

    @Test
    void createIsIdempotentOnTheTaskId() {
        Task task = task("t-1", null, Set.of("finance"), Task.Status.OPEN);

        assertThat(store.create(task)).isTrue();
        assertThat(store.create(task)).as("R10: creating the same task twice is a no-op").isFalse();
        assertThat(TestDatabases.count(DS, "lifecycle_task")).isEqualTo(1);
    }

    @Test
    void aTaskSurvivesTheRoundTripWhole() {
        Task task = task("t-2", "acme", Set.of("finance", "support"), Task.Status.OPEN);
        store.create(task);

        Task back = store.find("t-2").orElseThrow();
        assertThat(back.taskId()).isEqualTo("t-2");
        assertThat(back.tenantId()).isEqualTo("acme");
        assertThat(back.name()).isEqualTo("approve-refund");
        assertThat(back.assignTo()).containsExactlyInAnyOrder("finance", "support");
        assertThat(back.createdBy()).isEqualTo(new EntityRef("acme", "order", "o-1"));
        assertThat(back.createdByEventId()).isEqualTo("e-1");
        assertThat(back.onComplete().action()).isEqualTo("REFUND_DECIDED");
        assertThat(back.onComplete().target()).isEqualTo(new EntityRef("acme", "order", "o-1"));
        assertThat(back.payload()).containsEntry("reason", "damaged").containsEntry("amount", 42);
        assertThat(back.status()).isEqualTo(Task.Status.OPEN);
        assertThat(back.claimedBy()).isNull();
        assertThat(back.createdAt()).isEqualTo(T0);
        assertThat(back.updatedAt()).isEqualTo(T0);
        assertThat(back.causation()).isEqualTo(new Causation("corr-1", "e-0", 1));
        assertThat(back.isOpen()).isTrue();
    }

    @Test
    void findOfAnUnknownTaskIsEmpty() {
        assertThat(store.find("nope")).isEmpty();
    }

    @Test
    void transitionIsACompareAndSetOnStatus() {
        Task task = task("t-3", null, Set.of("finance"), Task.Status.OPEN);
        store.create(task);

        Task claimed = task.with(Task.Status.CLAIMED, "alice", T0.plusSeconds(10));
        assertThat(store.transition("t-3", Task.Status.OPEN, claimed)).isTrue();
        assertThat(store.find("t-3").orElseThrow().status()).isEqualTo(Task.Status.CLAIMED);
        assertThat(store.find("t-3").orElseThrow().claimedBy()).isEqualTo("alice");
        assertThat(store.find("t-3").orElseThrow().updatedAt()).isEqualTo(T0.plusSeconds(10));

        assertThat(store.transition("t-3", Task.Status.OPEN, claimed)).as("the status has moved on").isFalse();
        assertThat(store.transition("unknown-task", Task.Status.OPEN, claimed)).isFalse();
    }

    @Test
    void exactlyOneOfEightClaimantsWins() throws Exception {
        store.create(task("t-4", null, Set.of("finance"), Task.Status.OPEN));

        int threads = 8;
        AtomicInteger winners = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            String claimant = "u" + i;
            new Thread(() -> {
                try {
                    start.await();
                    Task current = store.find("t-4").orElseThrow();
                    if (store.transition("t-4", Task.Status.OPEN, current.with(Task.Status.CLAIMED, claimant, T0.plusSeconds(1)))) {
                        winners.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "claim-" + i).start();
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(winners.get()).as("a compare-and-set has exactly one winner").isEqualTo(1);
        Task claimed = store.find("t-4").orElseThrow();
        assertThat(claimed.status()).isEqualTo(Task.Status.CLAIMED);
        assertThat(claimed.claimedBy()).startsWith("u");
    }

    @Test
    void openForFiltersByRoleTenantAndStatus() {
        store.create(task("finance-open", null, Set.of("finance"), Task.Status.OPEN));
        store.create(task("support-open", null, Set.of("support"), Task.Status.OPEN));
        store.create(task("acme-finance", "acme", Set.of("finance"), Task.Status.OPEN));
        store.create(task("finance-done", null, Set.of("finance"), Task.Status.COMPLETED));
        store.create(task("finance-claimed", null, Set.of("finance"), Task.Status.CLAIMED));
        store.create(task("unassigned", null, Set.of(), Task.Status.OPEN));

        assertThat(store.openFor(null, Set.of("finance"), 10))
                .as("null tenant sees every tenant; unassigned work is visible to anyone")
                .extracting(Task::taskId)
                .containsExactlyInAnyOrder("finance-open", "acme-finance", "finance-claimed", "unassigned");

        assertThat(store.openFor("acme", Set.of("finance"), 10))
                .extracting(Task::taskId).containsExactly("acme-finance");

        assertThat(store.openFor(null, Set.of("support"), 10))
                .extracting(Task::taskId).containsExactlyInAnyOrder("support-open", "unassigned");

        assertThat(store.openFor(null, Set.of("nobody"), 10))
                .extracting(Task::taskId).containsExactly("unassigned");

        assertThat(store.openFor(null, Set.of("finance"), 2)).as("the limit is honoured").hasSize(2);
        assertThat(store.openFor(null, Set.of("finance"), 0)).isEmpty();
    }

    @Test
    void byEntityFindsEveryTaskACreatorRaised() {
        store.create(task("t-a", null, Set.of("finance"), Task.Status.OPEN));
        store.create(task("t-b", null, Set.of("finance"), Task.Status.COMPLETED));
        Task other = new Task("t-c", null, "other", Set.of("finance"), EntityRef.of("order", "o-2"), "e-2",
                new Task.OnComplete("X", EntityRef.of("order", "o-2")), Map.of(), Task.Status.OPEN, null,
                T0, T0, Causation.root("e-2"));
        store.create(other);

        List<Task> mine = store.byEntity(EntityRef.of("order", "o-1"));
        assertThat(mine).extracting(Task::taskId).containsExactly("t-a", "t-b");
        assertThat(store.byEntity(EntityRef.of("order", "o-2"))).extracting(Task::taskId).containsExactly("t-c");
        assertThat(store.byEntity(EntityRef.of("order", "never"))).isEmpty();
    }

    private static Task task(String taskId, String tenantId, Set<String> assignTo, Task.Status status) {
        EntityRef order = new EntityRef(tenantId, "order", "o-1");
        return new Task(taskId, tenantId, "approve-refund", assignTo, order, "e-1",
                new Task.OnComplete("REFUND_DECIDED", order),
                Map.of("reason", "damaged", "amount", 42),
                status, null, T0.truncatedTo(ChronoUnit.MILLIS), T0.truncatedTo(ChronoUnit.MILLIS),
                new Causation("corr-1", "e-0", 1));
    }
}
