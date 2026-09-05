package com.github.ifrugal.lifecycle.core.acceptance;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.EventKind;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.Outcome;
import com.github.ifrugal.lifecycle.api.rules.Dispatch;
import com.github.ifrugal.lifecycle.core.engine.DefaultLifecycleEngine;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryStateStore;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryTransport;
import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import com.github.ifrugal.lifecycle.core.registry.InMemoryDefinitionSource;
import com.github.ifrugal.lifecycle.core.rules.CompiledEmit;
import com.github.ifrugal.lifecycle.core.rules.GuardRegistry;
import com.github.ifrugal.lifecycle.core.rules.Machine;
import com.github.ifrugal.lifecycle.core.rules.RuleCompiler;
import com.github.ifrugal.lifecycle.core.testdomain.RefundWindowOpen;
import com.github.ifrugal.lifecycle.core.testdomain.SampleRules;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** DD-10: `task:` compiles to a `lifecycle.task.create` notification; the tasks module raises the deferred signal. */
class TaskCompileTest {

    @Test
    void requestRefundTaskCompilesToATaskCreateNotification() {
        RuleCompiler.Result result = new RuleCompiler().compile(SampleRules.order(), GuardRegistry.of(new RefundWindowOpen()), Set.of("order", "shipment"));
        assertThat(result.ok()).as("compile problems: %s", result.problems()).isTrue();
        Machine machine = result.machine();

        CompiledEmit taskEmit = machine.forAction("REQUEST_REFUND").get(0).emit().stream()
                .filter(e -> e.action().equals(RuleCompiler.TASK_CREATE_ACTION))
                .findFirst().orElseThrow();

        assertThat(taskEmit.action()).isEqualTo("lifecycle.task.create");
        assertThat(taskEmit.kind()).isEqualTo(EventKind.NOTIFICATION);
        assertThat(taskEmit.dispatch()).isEqualTo(Dispatch.TRANSPORT);
    }

    @Test
    void firingTheTransitionEmitsTheFullyProjectedTaskNotification() {
        var store = new InMemoryStateStore();
        var guards = GuardRegistry.of(new RefundWindowOpen());
        var registry = new DefinitionRegistry(new InMemoryDefinitionSource(SampleRules.all()), guards, store);
        registry.reloadOrThrow();

        try (var transport = new InMemoryTransport()) {
            var engine = new DefaultLifecycleEngine(registry, store, transport, guards);
            EntityRef order = EntityRef.of("order", "o-task");

            engine.handle(LifecycleEvent.builder().entity(order).action("PAY").actor(Actor.of("u1", "customer"))
                    .payload(Map.of("payment", Map.of("status", "AUTHORISED"), "shipmentId", "s-task")).build());
            engine.handle(LifecycleEvent.builder().entity(order).action("SHIPMENT_PREPARED").actor(Actor.service("sys", SampleRules.ENGINE_ROLE)).build());
            engine.handle(LifecycleEvent.builder().entity(order).action("COMPLETE").actor(Actor.service("sys", SampleRules.ENGINE_ROLE)).build());

            Outcome refundOutcome = engine.handle(LifecycleEvent.builder()
                    .entity(order).action("REQUEST_REFUND").actor(Actor.of("u1", "customer"))
                    .payload(Map.of("reason", "damaged"))
                    .build());
            assertThat(refundOutcome).isInstanceOf(Outcome.Applied.class);

            LifecycleEvent taskNotification = transport.notifications().stream()
                    .filter(n -> n.action().equals("lifecycle.task.create"))
                    .findFirst().orElseThrow();

            Map<String, Object> payload = taskNotification.payload();
            assertThat(payload.get("name")).isEqualTo("approve-refund");
            assertThat(payload.get("assignTo")).isEqualTo(java.util.List.of("finance"));

            @SuppressWarnings("unchecked")
            Map<String, Object> createdBy = (Map<String, Object>) payload.get("createdBy");
            assertThat(createdBy.get("type")).isEqualTo("order");
            assertThat(createdBy.get("id")).isEqualTo("o-task");
            assertThat(createdBy).containsEntry("tenant", null);

            @SuppressWarnings("unchecked")
            Map<String, Object> onComplete = (Map<String, Object>) payload.get("onComplete");
            assertThat(onComplete.get("action")).isEqualTo("REFUND_DECIDED");
            @SuppressWarnings("unchecked")
            Map<String, Object> target = (Map<String, Object>) onComplete.get("target");
            assertThat(target.get("type")).isEqualTo("order");
            assertThat(target.get("id")).isEqualTo("o-task");

            @SuppressWarnings("unchecked")
            Map<String, Object> taskPayload = (Map<String, Object>) payload.get("payload");
            assertThat(taskPayload).containsEntry("reason", "damaged");

            // simulate lifecycle-tasks completing the task: raise the deferred onComplete signal
            Outcome decided = engine.handle(LifecycleEvent.builder()
                    .entity(order).action("REFUND_DECIDED").actor(Actor.service("tasks", "lifecycle-tasks"))
                    .payload(Map.of("decision", "APPROVED"))
                    .build());
            assertThat(decided).isInstanceOf(Outcome.Applied.class);
            assertThat(((Outcome.Applied) decided).to()).isEqualTo("REFUNDED");
            assertThat(store.find(order).map(r -> r.state())).contains("REFUNDED");
        }
    }
}
