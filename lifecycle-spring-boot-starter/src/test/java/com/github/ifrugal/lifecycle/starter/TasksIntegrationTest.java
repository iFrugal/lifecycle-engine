package com.github.ifrugal.lifecycle.starter;

import com.github.ifrugal.lifecycle.api.LifecycleEngine;
import com.github.ifrugal.lifecycle.api.guard.GuardContext;
import com.github.ifrugal.lifecycle.api.guard.GuardPredicate;
import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.Outcome;
import com.github.ifrugal.lifecycle.api.spi.StateStore;
import com.github.ifrugal.lifecycle.starter.testapp.Events;
import com.github.ifrugal.lifecycle.starter.testapp.TestApplication;
import com.github.ifrugal.lifecycle.tasks.InMemoryTaskStore;
import com.github.ifrugal.lifecycle.tasks.Task;
import com.github.ifrugal.lifecycle.tasks.TaskService;
import com.github.ifrugal.lifecycle.tasks.TaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@code lifecycle.tasks.enabled=true} over the in-memory transport (DD-10, DD-13 bean 9): the {@code task:} on
 * {@code order.request-refund} compiles to a {@code lifecycle.task.create} notification, the wired listener turns
 * it into a task, and completing that task raises the deferred {@code REFUND_DECIDED} signal that moves the order.
 */
@SpringBootTest(
        classes = {TestApplication.class, TasksIntegrationTest.Guards.class},
        properties = {
                "lifecycle.rules.files=classpath:rules/order.yaml,classpath:rules/shipment.yaml",
                "lifecycle.rules.reload.poll=0",
                "lifecycle.tasks.enabled=true"
        })
class TasksIntegrationTest {

    @Configuration(proxyBeanMethods = false)
    static class Guards {
        @Bean
        GuardPredicate refundWindowOpen() {
            return new GuardPredicate() {
                @Override
                public String name() {
                    return "refund-window-open";
                }

                @Override
                public boolean test(GuardContext context) {
                    return true;
                }
            };
        }
    }

    private static final Actor FINANCE = new Actor("f-1", Set.of("finance"), Actor.Kind.HUMAN);

    @Autowired
    LifecycleEngine engine;
    @Autowired
    StateStore store;
    @Autowired
    TaskService tasks;
    @Autowired
    TaskStore taskStore;
    @Autowired
    LifecycleTasksAutoConfiguration.InMemoryTaskNotificationBridge bridge;

    @Test
    void wires_the_in_memory_task_store_and_the_notification_bridge() {
        assertThat(taskStore).isInstanceOf(InMemoryTaskStore.class);
        assertThat(bridge.isAttached()).isTrue();
    }

    @Test
    void a_task_is_created_by_the_rule_and_completing_it_advances_the_entity() {
        EntityRef order = EntityRef.of("order", "o-task");
        driveToCompleted("o-task", "s-task");

        Outcome refundRequested = engine.handle(Events.action("order", "o-task", "REQUEST_REFUND", "customer",
                Map.of("reason", "arrived damaged")));
        assertThat(refundRequested).isInstanceOf(Outcome.Applied.class);
        assertThat(store.find(order).orElseThrow().state()).isEqualTo("REFUND_PENDING");

        Task task = await().atMost(Duration.ofSeconds(5))
                .until(() -> taskStore.byEntity(order).stream().findFirst().orElse(null), t -> t != null);
        assertThat(task.name()).isEqualTo("approve-refund");
        assertThat(task.assignTo()).containsExactly("finance");
        assertThat(task.status()).isEqualTo(Task.Status.OPEN);
        assertThat(task.payload()).containsEntry("reason", "arrived damaged");

        tasks.complete(task.taskId(), FINANCE, Map.of("decision", "APPROVED"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(store.find(order).orElseThrow().state()).isEqualTo("REFUNDED"));
        assertThat(tasks.find(task.taskId()).orElseThrow().status()).isEqualTo(Task.Status.COMPLETED);
    }

    /** NEW -> PAID -> FULFILLING -> COMPLETED, driven entirely by the rules' own cross-entity cascade. */
    private void driveToCompleted(String orderId, String shipmentId) {
        EntityRef order = EntityRef.of("order", orderId);
        engine.handle(Events.pay(orderId, shipmentId));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(store.find(order).orElseThrow().state()).isEqualTo("FULFILLING"));

        engine.handle(Events.action("shipment", shipmentId, "DELIVER", "courier", Map.of("orderId", orderId)));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(store.find(order).orElseThrow().state()).isEqualTo("COMPLETED"));
    }
}
