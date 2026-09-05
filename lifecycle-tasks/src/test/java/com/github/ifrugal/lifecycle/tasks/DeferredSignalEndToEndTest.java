package com.github.ifrugal.lifecycle.tasks;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.AuditRecord;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.Outcome;
import com.github.ifrugal.lifecycle.api.model.StateRecord;
import com.github.ifrugal.lifecycle.core.engine.DefaultLifecycleEngine;
import com.github.ifrugal.lifecycle.core.engine.Dispatcher;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryStateStore;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryTransport;
import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import com.github.ifrugal.lifecycle.core.registry.InMemoryDefinitionSource;
import com.github.ifrugal.lifecycle.core.rules.GuardRegistry;
import com.github.ifrugal.lifecycle.tasks.testdomain.RefundFixture;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End to end through the real engine, transport and registry (DD-09, DD-10): a rule declares a task, this
 * module turns the resulting {@code lifecycle.task.create} notification into a task, and completing it raises
 * the deferred {@code onComplete} signal back through the same transport into the engine.
 */
class DeferredSignalEndToEndTest {

    @Test
    void requestRefundCreatesATaskThatFinanceAloneMayCompleteAndTheDecisionAdvancesTheOrder() {
        InMemoryStateStore store = new InMemoryStateStore();
        GuardRegistry guards = GuardRegistry.empty();
        DefinitionRegistry registry = new DefinitionRegistry(new InMemoryDefinitionSource(RefundFixture.all()), guards, store);
        registry.reloadOrThrow();

        try (InMemoryTransport transport = new InMemoryTransport()) {
            DefaultLifecycleEngine engine = new DefaultLifecycleEngine(registry, store, transport, guards);
            new Dispatcher(engine).attachTo(transport);

            InMemoryTaskStore taskStore = new InMemoryTaskStore();
            TaskService taskService = new TaskService(taskStore, transport);
            transport.onNotification(taskService.asNotificationListener());

            EntityRef order = EntityRef.of("order", "o-1");
            String requestId = "req-refund-1";
            Outcome refundOutcome = engine.handle(LifecycleEvent.builder()
                    .eventId(requestId)
                    .entity(order).action("REQUEST_REFUND").actor(Actor.of("cust-1", "customer"))
                    .payload(Map.of("reason", "damaged"))
                    .build());

            assertThat(refundOutcome).isInstanceOf(Outcome.Applied.class);
            assertThat(store.find(order).map(StateRecord::state)).contains("REFUND_PENDING");

            List<Task> financeTasks = taskService.openFor(Actor.of("finance-user", "finance"), null, 10);
            assertThat(financeTasks).hasSize(1);
            Task task = financeTasks.get(0);
            assertThat(task.name()).isEqualTo("approve-refund");
            assertThat(task.status()).isEqualTo(Task.Status.OPEN);
            assertThat(task.createdBy()).isEqualTo(order);
            assertThat(task.onComplete().target()).isEqualTo(order);
            assertThat(task.payload()).containsEntry("reason", "damaged");

            assertThat(taskService.openFor(Actor.of("cust-1", "customer"), null, 10)).isEmpty();
            assertThatThrownBy(() -> taskService.complete(task.taskId(), Actor.of("cust-1", "customer"), Map.of("decision", "APPROVED")))
                    .isInstanceOf(NotAssigneeException.class);

            LifecycleEvent decision = taskService.complete(task.taskId(), Actor.of("finance-user", "finance"), Map.of("decision", "APPROVED"));
            assertThat(transport.awaitIdle(Duration.ofSeconds(2))).isTrue();

            assertThat(store.find(order).map(StateRecord::state)).contains("REFUNDED");

            AuditRecord decidedRow = store.byEntity(order).stream()
                    .filter(a -> a.eventId().equals(decision.eventId()))
                    .findFirst().orElseThrow();
            assertThat(decidedRow.actor().id()).isEqualTo("finance-user");
            assertThat(decidedRow.actor().roles()).contains(TaskService.TASKS_ROLE);
            assertThat(decidedRow.causation().hop()).isEqualTo(2);
            assertThat(decidedRow.causation().correlationId()).isEqualTo(requestId);
        }
    }

    @Test
    void aTaskCreatedByOneEntityCanAdvanceAnother() {
        InMemoryStateStore store = new InMemoryStateStore();
        GuardRegistry guards = GuardRegistry.empty();
        DefinitionRegistry registry = new DefinitionRegistry(new InMemoryDefinitionSource(RefundFixture.all()), guards, store);
        registry.reloadOrThrow();

        try (InMemoryTransport transport = new InMemoryTransport()) {
            DefaultLifecycleEngine engine = new DefaultLifecycleEngine(registry, store, transport, guards);
            new Dispatcher(engine).attachTo(transport);

            InMemoryTaskStore taskStore = new InMemoryTaskStore();
            TaskService taskService = new TaskService(taskStore, transport);
            transport.onNotification(taskService.asNotificationListener());

            EntityRef order = EntityRef.of("order", "o-2");
            EntityRef shipment = EntityRef.of("shipment", "sh-1");

            Outcome holdOutcome = engine.handle(LifecycleEvent.builder()
                    .entity(order).action("REQUEST_HOLD").actor(Actor.of("cust-2", "customer"))
                    .payload(Map.of("shipmentId", "sh-1"))
                    .build());
            assertThat(holdOutcome).isInstanceOf(Outcome.Applied.class);

            List<Task> opsTasks = taskService.openFor(Actor.of("ops-user", "ops"), null, 10);
            assertThat(opsTasks).hasSize(1);
            Task task = opsTasks.get(0);
            assertThat(task.createdBy()).isEqualTo(order);
            assertThat(task.onComplete().target()).isEqualTo(shipment);

            assertThat(store.find(shipment)).isEmpty();

            taskService.complete(task.taskId(), Actor.of("ops-user", "ops"), Map.of());
            assertThat(transport.awaitIdle(Duration.ofSeconds(2))).isTrue();

            assertThat(store.find(shipment).map(StateRecord::state)).contains("RELEASED");
            assertThat(store.find(order).map(StateRecord::state)).contains("COMPLETED");
        }
    }
}
