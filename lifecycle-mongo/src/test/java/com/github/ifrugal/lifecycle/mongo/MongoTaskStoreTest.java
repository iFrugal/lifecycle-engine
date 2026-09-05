package com.github.ifrugal.lifecycle.mongo;

import com.github.ifrugal.lifecycle.api.model.Causation;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.tasks.Task;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.github.ifrugal.lifecycle.mongo.TestSupport.id;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class MongoTaskStoreTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

    private MongoClient client;
    private MongoDatabase database;
    private MongoTaskStore store;

    @BeforeEach
    void setUp() {
        client = MongoClients.create(MONGO.getConnectionString());
        database = client.getDatabase("test_" + id().replace("-", ""));
        MongoSchema.ensureIndexes(database);
        store = new MongoTaskStore(database);
    }

    @AfterEach
    void tearDown() {
        database.drop();
        client.close();
    }

    private static Task newTask(String taskId, String tenantId, Set<String> assignTo, EntityRef createdBy) {
        Instant now = Instant.now();
        return new Task(taskId, tenantId, "approve-refund", assignTo, createdBy, id(),
                new Task.OnComplete("REFUND_DECIDED", createdBy), Map.of("reason", "customer request"),
                Task.Status.OPEN, null, now, now, Causation.root(id()));
    }

    @Test
    void createIsIdempotentOnTaskId() {
        EntityRef order = EntityRef.of("order", "o1");
        Task task = newTask("task-1", null, Set.of("finance"), order);

        assertThat(store.create(task)).isTrue();
        assertThat(store.create(task)).as("creating the same task id again is a no-op").isFalse();
        assertThat(store.find("task-1")).isPresent();
    }

    @Test
    void transitionIsCompareAndSetUnderConcurrency() throws Exception {
        EntityRef order = EntityRef.of("order", "o-cas");
        Task task = newTask("task-cas", null, Set.of("finance"), order);
        store.create(task);

        int threads = 8;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var successes = new ConcurrentLinkedQueue<String>();

        for (int i = 0; i < threads; i++) {
            String claimant = "agent-" + i;
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    Task claimed = task.with(Task.Status.CLAIMED, claimant, Instant.now());
                    if (store.transition("task-cas", Task.Status.OPEN, claimed)) {
                        successes.add(claimant);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            worker.start();
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(successes).as("exactly one claimant wins the compare-and-set").hasSize(1);
        Task found = store.find("task-cas").orElseThrow();
        assertThat(found.status()).isEqualTo(Task.Status.CLAIMED);
        assertThat(found.claimedBy()).isEqualTo(successes.peek());

        // a second claim attempt against the now-CLAIMED task fails cleanly.
        assertThat(store.transition("task-cas", Task.Status.OPEN, task.with(Task.Status.CLAIMED, "late-agent", Instant.now()))).isFalse();
    }

    @Test
    void openForFiltersByTenantRoleAndStatus() {
        EntityRef order = EntityRef.of("order", "o2");
        Task acmeFinance = newTask("t-acme-finance", "acme", Set.of("finance"), order);
        Task acmeSupport = newTask("t-acme-support", "acme", Set.of("support"), order);
        Task globexFinance = newTask("t-globex-finance", "globex", Set.of("finance"), order);
        Task acmeCompleted = newTask("t-acme-completed", "acme", Set.of("finance"), order)
                .with(Task.Status.COMPLETED, null, Instant.now());

        store.create(acmeFinance);
        store.create(acmeSupport);
        store.create(globexFinance);
        store.create(acmeCompleted);

        List<Task> acmeFinanceTasks = store.openFor("acme", Set.of("finance"), 10);
        assertThat(acmeFinanceTasks).extracting(Task::taskId).containsExactly("t-acme-finance");

        List<Task> allFinanceAcrossTenants = store.openFor(null, Set.of("finance"), 10);
        assertThat(allFinanceAcrossTenants).extracting(Task::taskId).containsExactlyInAnyOrder("t-acme-finance", "t-globex-finance");

        List<Task> acmeAnyOfBothRoles = store.openFor("acme", Set.of("finance", "support"), 10);
        assertThat(acmeAnyOfBothRoles).extracting(Task::taskId).containsExactlyInAnyOrder("t-acme-finance", "t-acme-support");
    }

    @Test
    void claimedTasksAreStillOpenForButCompletedAreNot() {
        EntityRef order = EntityRef.of("order", "o3");
        Task claimed = newTask("t-claimed", null, Set.of("finance"), order).with(Task.Status.CLAIMED, "agent-1", Instant.now());
        Task completed = newTask("t-completed", null, Set.of("finance"), order).with(Task.Status.COMPLETED, "agent-1", Instant.now());
        store.create(claimed);
        store.create(completed);

        List<Task> open = store.openFor(null, Set.of("finance"), 10);
        assertThat(open).extracting(Task::taskId).containsExactly("t-claimed");
    }

    @Test
    void byEntityFindsTasksCreatedByAGivenEntity() {
        EntityRef order1 = EntityRef.of("order", "o-a");
        EntityRef order2 = EntityRef.of("order", "o-b");
        store.create(newTask("t1", null, Set.of("finance"), order1));
        store.create(newTask("t2", null, Set.of("finance"), order1));
        store.create(newTask("t3", null, Set.of("finance"), order2));

        assertThat(store.byEntity(order1)).extracting(Task::taskId).containsExactlyInAnyOrder("t1", "t2");
        assertThat(store.byEntity(order2)).extracting(Task::taskId).containsExactly("t3");
    }
}
