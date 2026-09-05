package com.github.ifrugal.lifecycle.tasks;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.Causation;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.EventKind;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryTransport;
import com.github.ifrugal.lifecycle.core.rules.RuleCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskServiceTest {

    private final InMemoryTaskStore store = new InMemoryTaskStore();
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private InMemoryTransport transport;
    private TaskService service;

    @BeforeEach
    void setUp() {
        transport = new InMemoryTransport();
        service = new TaskService(store, transport, clock);
    }

    @AfterEach
    void tearDown() {
        transport.close();
    }

    private static String taskIdFor(String eventId) {
        return UUID.nameUUIDFromBytes(("task:" + eventId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** Builds a notification with exactly the payload shape {@code RuleCompiler.compileTask} produces. */
    private static LifecycleEvent taskCreateNotification(String eventId, String name, List<String> assignTo,
            EntityRef createdBy, String onCompleteAction, EntityRef onCompleteTarget, Map<String, Object> payload,
            Causation causation) {
        Map<String, Object> createdByMap = new LinkedHashMap<>();
        createdByMap.put("type", createdBy.type());
        createdByMap.put("id", createdBy.id());
        createdByMap.put("tenant", createdBy.tenantId());
        Map<String, Object> targetMap = new LinkedHashMap<>();
        targetMap.put("type", onCompleteTarget.type());
        targetMap.put("id", onCompleteTarget.id());
        Map<String, Object> onComplete = new LinkedHashMap<>();
        onComplete.put("action", onCompleteAction);
        onComplete.put("target", targetMap);
        Map<String, Object> notifPayload = new LinkedHashMap<>();
        notifPayload.put("name", name);
        notifPayload.put("assignTo", assignTo);
        notifPayload.put("createdBy", createdByMap);
        notifPayload.put("onComplete", onComplete);
        notifPayload.put("payload", payload);

        return new LifecycleEvent(eventId, EventKind.NOTIFICATION, createdBy, RuleCompiler.TASK_CREATE_ACTION,
                new Actor("engine", Set.of("lifecycle-engine"), Actor.Kind.ENGINE), notifPayload, Instant.now(),
                null, causation, null);
    }

    private Task createOpenTask(String taskId, Set<String> assignTo) {
        Task t = new Task(taskId, null, "approve-refund", assignTo, EntityRef.of("order", "o-1"),
                "notif-" + taskId, new Task.OnComplete("REFUND_DECIDED", EntityRef.of("order", "o-1")),
                Map.of("reason", "damaged"), Task.Status.OPEN, null, clock.instant(), null,
                Causation.root("notif-" + taskId));
        store.create(t);
        return t;
    }

    @Test
    void acceptIgnoresNonTaskNotificationsAndSignals() {
        LifecycleEvent otherNotification = LifecycleEvent.builder()
                .eventId("e1").kind(EventKind.NOTIFICATION).entity(EntityRef.of("order", "o-1"))
                .action("SomethingHappened").actor(Actor.of("u1")).occurredAt(Instant.now()).build();
        assertThat(service.accept(otherNotification)).isFalse();

        LifecycleEvent signal = LifecycleEvent.builder()
                .eventId("e2").kind(EventKind.SIGNAL).entity(EntityRef.of("order", "o-1"))
                .action(RuleCompiler.TASK_CREATE_ACTION).actor(Actor.of("u1")).occurredAt(Instant.now()).build();
        assertThat(service.accept(signal)).isFalse();

        assertThat(store.find(taskIdFor("e1"))).isEmpty();
        assertThat(store.find(taskIdFor("e2"))).isEmpty();
    }

    @Test
    void acceptParsesTheExactCompilerPayloadShapeIntoATask() {
        EntityRef order = EntityRef.of("order", "o-1");
        Causation causation = Causation.root("req-1").child("req-1");
        LifecycleEvent notification = taskCreateNotification("notif-1", "approve-refund", List.of("finance"),
                order, "REFUND_DECIDED", order, Map.of("reason", "damaged"), causation);

        assertThat(service.accept(notification)).isTrue();

        Task task = store.find(taskIdFor("notif-1")).orElseThrow();
        assertThat(task.name()).isEqualTo("approve-refund");
        assertThat(task.assignTo()).containsExactly("finance");
        assertThat(task.createdBy()).isEqualTo(order);
        assertThat(task.createdByEventId()).isEqualTo("notif-1");
        assertThat(task.onComplete().action()).isEqualTo("REFUND_DECIDED");
        assertThat(task.onComplete().target()).isEqualTo(order);
        assertThat(task.payload()).containsEntry("reason", "damaged");
        assertThat(task.status()).isEqualTo(Task.Status.OPEN);
        assertThat(task.causation()).isEqualTo(causation);
    }

    @Test
    void acceptOnADuplicateNotificationReturnsFalseAndCreatesNoSecondTask() {
        EntityRef order = EntityRef.of("order", "o-1");
        LifecycleEvent notification = taskCreateNotification("notif-dup", "approve-refund", List.of("finance"),
                order, "REFUND_DECIDED", order, Map.of(), Causation.root("notif-dup"));

        assertThat(service.accept(notification)).isTrue();
        assertThat(service.accept(notification)).isFalse();
    }

    @Test
    void acceptRejectsAMalformedPayloadWithTheEventIdInTheMessage() {
        LifecycleEvent bad = LifecycleEvent.builder()
                .eventId("bad-1").kind(EventKind.NOTIFICATION).entity(EntityRef.of("order", "o-1"))
                .action(RuleCompiler.TASK_CREATE_ACTION).actor(Actor.of("u1"))
                .payload(Map.of("assignTo", List.of("finance"))) // no name, no createdBy, no onComplete
                .occurredAt(Instant.now()).build();

        assertThatThrownBy(() -> service.accept(bad))
                .isInstanceOf(MalformedTaskEventException.class)
                .hasMessageContaining("bad-1");
    }

    @Test
    void asNotificationListenerDelegatesToAccept() {
        Consumer<LifecycleEvent> listener = service.asNotificationListener();
        EntityRef order = EntityRef.of("order", "o-1");
        LifecycleEvent notification = taskCreateNotification("notif-listener", "approve-refund", List.of(),
                order, "REFUND_DECIDED", order, Map.of(), Causation.root("notif-listener"));

        listener.accept(notification);

        assertThat(store.find(taskIdFor("notif-listener"))).isPresent();
    }

    @Test
    void claimRequiresAnAssigneeRoleAndIsCasProtected() {
        Task task = createOpenTask("t-claim", Set.of("finance"));

        assertThatThrownBy(() -> service.claim(task.taskId(), Actor.of("u1", "ops")))
                .isInstanceOf(NotAssigneeException.class);

        Task claimed = service.claim(task.taskId(), Actor.of("u2", "finance"));
        assertThat(claimed.status()).isEqualTo(Task.Status.CLAIMED);
        assertThat(claimed.claimedBy()).isEqualTo("u2");

        assertThatThrownBy(() -> service.claim(task.taskId(), Actor.of("u3", "finance")))
                .isInstanceOf(TaskNotOpenException.class);
    }

    @Test
    void claimAcceptsAnyoneWhenAssignToIsEmpty() {
        Task task = createOpenTask("t-claim-open", Set.of());

        Task claimed = service.claim(task.taskId(), Actor.of("anyone"));

        assertThat(claimed.status()).isEqualTo(Task.Status.CLAIMED);
    }

    @Test
    void claimOnUnknownTaskThrowsTaskNotFound() {
        assertThatThrownBy(() -> service.claim("missing", Actor.of("u1")))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void releaseReturnsAClaimedTaskToOpenForTheClaimant() {
        Task task = createOpenTask("t-release", Set.of());
        Actor actor = Actor.of("u1");
        service.claim(task.taskId(), actor);

        Task released = service.release(task.taskId(), actor);

        assertThat(released.status()).isEqualTo(Task.Status.OPEN);
        assertThat(released.claimedBy()).isNull();
    }

    @Test
    void releaseByNonClaimantThrows() {
        Task task = createOpenTask("t-release-2", Set.of());
        service.claim(task.taskId(), Actor.of("u1"));

        assertThatThrownBy(() -> service.release(task.taskId(), Actor.of("u2")))
                .isInstanceOf(TaskNotOpenException.class);
    }

    @Test
    void completePublishesADeferredSignalWithMergedPayloadAndTasksRole() throws Exception {
        List<LifecycleEvent> received = new CopyOnWriteArrayList<>();
        transport.subscribe(received::add);

        EntityRef order = EntityRef.of("order", "o-9");
        Causation notifCausation = Causation.root("req-9").child("req-9"); // hop 1
        Task task = new Task("t-complete", null, "approve-refund", Set.of("finance"), order, "notif-9",
                new Task.OnComplete("REFUND_DECIDED", order), Map.of("reason", "damaged"), Task.Status.OPEN, null,
                clock.instant(), null, notifCausation);
        store.create(task);

        Actor finance = Actor.of("finance-user", "finance");
        LifecycleEvent event = service.complete(task.taskId(), finance, Map.of("decision", "APPROVED"));

        assertThat(event.kind()).isEqualTo(EventKind.SIGNAL);
        assertThat(event.entity()).isEqualTo(order);
        assertThat(event.action()).isEqualTo("REFUND_DECIDED");
        assertThat(event.actor().id()).isEqualTo("finance-user");
        assertThat(event.actor().roles()).contains("finance", TaskService.TASKS_ROLE);
        assertThat(event.payload())
                .containsEntry("reason", "damaged")
                .containsEntry("decision", "APPROVED")
                .containsEntry("taskId", "t-complete")
                .containsEntry("taskName", "approve-refund");
        assertThat(event.causation().hop()).isEqualTo(2);
        assertThat(event.causation().causationId()).isEqualTo("notif-9");
        assertThat(event.causation().correlationId()).isEqualTo("req-9");

        String expectedId = UUID.nameUUIDFromBytes(("task-complete:t-complete").getBytes(StandardCharsets.UTF_8)).toString();
        assertThat(event.eventId()).isEqualTo(expectedId);

        assertThat(transport.awaitIdle(Duration.ofSeconds(2))).isTrue();
        assertThat(received).extracting(LifecycleEvent::eventId).containsExactly(expectedId);

        assertThat(store.find("t-complete")).get().extracting(Task::status).isEqualTo(Task.Status.COMPLETED);
    }

    @Test
    void completeRequiresAnAssigneeRole() {
        Task task = createOpenTask("t-complete-role", Set.of("finance"));

        assertThatThrownBy(() -> service.complete(task.taskId(), Actor.of("u1", "ops"), Map.of()))
                .isInstanceOf(NotAssigneeException.class);
    }

    @Test
    void completingTwiceThrowsAndPublishesOnlyOneSignal() throws Exception {
        List<LifecycleEvent> received = new CopyOnWriteArrayList<>();
        transport.subscribe(received::add);
        Task task = createOpenTask("t-twice", Set.of());
        Actor actor = Actor.of("u1");

        service.complete(task.taskId(), actor, Map.of("decision", "APPROVED"));
        assertThatThrownBy(() -> service.complete(task.taskId(), actor, Map.of("decision", "APPROVED")))
                .isInstanceOf(TaskNotOpenException.class);

        assertThat(transport.awaitIdle(Duration.ofSeconds(2))).isTrue();
        assertThat(received).hasSize(1);
    }

    @Test
    void cancelChecksRoleAndEmitsNothing() throws Exception {
        List<LifecycleEvent> received = new CopyOnWriteArrayList<>();
        transport.subscribe(received::add);
        Task task = createOpenTask("t-cancel", Set.of("finance"));

        assertThatThrownBy(() -> service.cancel(task.taskId(), Actor.of("u1", "ops")))
                .isInstanceOf(NotAssigneeException.class);

        Task cancelled = service.cancel(task.taskId(), Actor.of("u2", "finance"));
        assertThat(cancelled.status()).isEqualTo(Task.Status.CANCELLED);

        assertThat(transport.awaitIdle(Duration.ofSeconds(1))).isTrue();
        assertThat(received).isEmpty();
    }

    @Test
    void openForAndFindDelegateToTheStore() {
        Task task = createOpenTask("t-open", Set.of());

        assertThat(service.find("t-open")).contains(task);
        assertThat(service.openFor(Actor.of("u1"), null, 10)).extracting(Task::taskId).contains("t-open");
    }
}
