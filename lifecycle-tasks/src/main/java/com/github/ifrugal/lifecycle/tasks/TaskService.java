package com.github.ifrugal.lifecycle.tasks;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.EventKind;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.spi.Transport;
import com.github.ifrugal.lifecycle.core.rules.RuleCompiler;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Consumes {@code lifecycle.task.create} notifications (DD-10), stores them as {@link Task}s and raises the
 * deferred {@code onComplete} signal when an assignee completes one. Depends only on {@link Transport} and
 * {@link TaskStore}: it does not know about {@link com.github.ifrugal.lifecycle.core.inmemory.InMemoryTransport}
 * or any other in-memory reference implementation.
 *
 * <p>{@link #complete} publishes its signal only after the task's status is committed to {@code COMPLETED}. If
 * the publish itself throws, the task is left {@code COMPLETED} and the exception propagates to the caller; a
 * retry of the same completion request is a safe no-op at the engine because the signal's id is deterministic
 * (the engine reports {@code Duplicate}), but this service's own CAS will refuse the retry with
 * {@link TaskNotOpenException} since the task is no longer open. Callers that must guarantee the signal was
 * actually sent should retry the publish out of band (e.g. via the transport's outbox), not this method.
 */
public final class TaskService {

    /** The role stamped onto the completing actor before the deferred signal is raised (DD-10). */
    public static final String TASKS_ROLE = "lifecycle-tasks";

    /** The reserved notification action the compiler emits for a {@code task:} declaration (DD-10). */
    public static final String TASK_CREATE_ACTION = RuleCompiler.TASK_CREATE_ACTION;

    private final TaskStore store;
    private final Transport transport;
    private final Clock clock;

    public TaskService(TaskStore store, Transport transport, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public TaskService(TaskStore store, Transport transport) {
        this(store, transport, Clock.systemUTC());
    }

    /**
     * Parses and stores a {@code lifecycle.task.create} notification. Returns {@code false} for anything that
     * is not one (kind or action mismatch) so callers may wire this straight into a transport's notification
     * feed alongside other listeners. The derived task id is deterministic on {@code notification.eventId()},
     * so redelivery of the same notification is a safe no-op (R10).
     *
     * @throws MalformedTaskEventException if the notification's action matches but its payload does not carry
     *                                      the shape the compiler produces
     */
    public boolean accept(LifecycleEvent notification) {
        if (notification.kind() != EventKind.NOTIFICATION || !TASK_CREATE_ACTION.equals(notification.action())) {
            return false;
        }
        Task task = parseTask(notification);
        return store.create(task);
    }

    /** @throws NotAssigneeException if the actor holds none of the task's {@code assignTo} roles
     *  @throws TaskNotOpenException if the task is not {@code OPEN} (including a lost race with another claimant) */
    public Task claim(String taskId, Actor actor) {
        Task task = requireTask(taskId);
        requireAssignee(task, actor);
        Instant now = clock.instant();
        Task updated = task.with(Task.Status.CLAIMED, actor.id(), now);
        if (!store.transition(taskId, Task.Status.OPEN, updated)) {
            throw notOpen(taskId, Task.Status.OPEN);
        }
        return updated;
    }

    /** @throws TaskNotOpenException if the task is not {@code CLAIMED} by this actor */
    public Task release(String taskId, Actor actor) {
        Task task = requireTask(taskId);
        if (task.status() != Task.Status.CLAIMED || !Objects.equals(task.claimedBy(), actor.id())) {
            throw notOpen(taskId, Task.Status.CLAIMED);
        }
        Instant now = clock.instant();
        Task updated = task.with(Task.Status.OPEN, null, now);
        if (!store.transition(taskId, Task.Status.CLAIMED, updated)) {
            throw notOpen(taskId, Task.Status.CLAIMED);
        }
        return updated;
    }

    /**
     * Marks the task {@code COMPLETED} and raises its {@code onComplete} signal, addressed to {@code target}
     * (the creating entity by default, or another one entirely: the deferred cross-entity signal). The
     * completing actor gains {@link #TASKS_ROLE} on the emitted event so a rule that only trusts this module
     * can list it in {@code roles}. {@code completion} is overlaid onto the task's stored payload (completion
     * wins on key conflicts), then {@code taskId} and {@code taskName} are stamped on top.
     *
     * @throws NotAssigneeException if the actor holds none of the task's {@code assignTo} roles
     * @throws TaskNotOpenException if the task is not {@code OPEN} or {@code CLAIMED}
     */
    public LifecycleEvent complete(String taskId, Actor actor, Map<String, Object> completion) {
        Task task = requireTask(taskId);
        requireAssignee(task, actor);
        Task.Status current = task.status();
        if (current != Task.Status.OPEN && current != Task.Status.CLAIMED) {
            throw notOpen(taskId, Task.Status.OPEN);
        }
        Instant now = clock.instant();
        Task updated = task.with(Task.Status.COMPLETED, task.claimedBy(), now);
        if (!store.transition(taskId, current, updated)) {
            throw notOpen(taskId, current);
        }

        Map<String, Object> mergedPayload = new LinkedHashMap<>(task.payload());
        if (completion != null) {
            mergedPayload.putAll(completion);
        }
        mergedPayload.put("taskId", task.taskId());
        mergedPayload.put("taskName", task.name());

        Set<String> roles = new LinkedHashSet<>(actor.roles());
        roles.add(TASKS_ROLE);
        Actor signalActor = new Actor(actor.id(), roles, actor.kind());

        LifecycleEvent event = new LifecycleEvent(
                UUID.nameUUIDFromBytes(("task-complete:" + taskId).getBytes(StandardCharsets.UTF_8)).toString(),
                EventKind.SIGNAL,
                task.onComplete().target(),
                task.onComplete().action(),
                signalActor,
                mergedPayload,
                now,
                null,
                task.causation().child(task.createdByEventId()),
                null);
        transport.publish(event);
        return event;
    }

    /** Cancels an open or claimed task. Emits nothing (DD-10).
     *  @throws NotAssigneeException if the actor holds none of the task's {@code assignTo} roles
     *  @throws TaskNotOpenException if the task is not {@code OPEN} or {@code CLAIMED} */
    public Task cancel(String taskId, Actor actor) {
        Task task = requireTask(taskId);
        requireAssignee(task, actor);
        Task.Status current = task.status();
        if (current != Task.Status.OPEN && current != Task.Status.CLAIMED) {
            throw notOpen(taskId, current);
        }
        Instant now = clock.instant();
        Task updated = task.with(Task.Status.CANCELLED, task.claimedBy(), now);
        if (!store.transition(taskId, current, updated)) {
            throw notOpen(taskId, current);
        }
        return updated;
    }

    public List<Task> openFor(Actor actor, String tenantId, int limit) {
        return store.openFor(tenantId, actor.roles(), limit);
    }

    public Optional<Task> find(String taskId) {
        return store.find(taskId);
    }

    /** So callers do {@code transport.onNotification(service.asNotificationListener())} without this module
     *  knowing anything about the transport that delivers the notification. */
    public Consumer<LifecycleEvent> asNotificationListener() {
        return this::accept;
    }

    private Task requireTask(String taskId) {
        return store.find(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    private static void requireAssignee(Task task, Actor actor) {
        if (!task.assignTo().isEmpty() && java.util.Collections.disjoint(task.assignTo(), actor.roles())) {
            throw new NotAssigneeException(task.taskId(), actor.id());
        }
    }

    private TaskNotOpenException notOpen(String taskId, Task.Status expected) {
        Task.Status actual = store.find(taskId).map(Task::status).orElse(null);
        return new TaskNotOpenException(taskId, expected, actual);
    }

    private Task parseTask(LifecycleEvent notification) {
        String eventId = notification.eventId();
        Map<String, Object> payload = notification.payload();

        String name = stringField(payload, "name", eventId);

        Set<String> assignTo = new LinkedHashSet<>();
        Object assignToRaw = payload.get("assignTo");
        if (assignToRaw != null) {
            if (!(assignToRaw instanceof List<?> list)) {
                throw new MalformedTaskEventException(eventId, "assignTo must be a list");
            }
            for (Object o : list) {
                assignTo.add(String.valueOf(o));
            }
        }

        Map<String, Object> createdByRaw = mapField(payload, "createdBy", eventId);
        String createdByType = stringField(createdByRaw, "type", eventId);
        String createdById = stringField(createdByRaw, "id", eventId);
        Object tenantRaw = createdByRaw.get("tenant");
        String tenantId = tenantRaw == null ? null : String.valueOf(tenantRaw);
        EntityRef createdBy = new EntityRef(tenantId, createdByType, createdById);

        Map<String, Object> onCompleteRaw = mapField(payload, "onComplete", eventId);
        String onCompleteAction = stringField(onCompleteRaw, "action", eventId);
        Map<String, Object> targetRaw = mapField(onCompleteRaw, "target", eventId);
        String targetType = stringField(targetRaw, "type", eventId);
        Object targetIdRaw = targetRaw.get("id");
        if (targetIdRaw == null) {
            throw new MalformedTaskEventException(eventId, "onComplete.target.id is required");
        }
        EntityRef target = new EntityRef(tenantId, targetType, String.valueOf(targetIdRaw));

        Map<String, Object> taskPayload = Map.of();
        Object taskPayloadRaw = payload.get("payload");
        if (taskPayloadRaw instanceof Map<?, ?> m) {
            taskPayload = castStringKeyed(m);
        } else if (taskPayloadRaw != null) {
            throw new MalformedTaskEventException(eventId, "payload must be a map");
        }

        String taskId = UUID.nameUUIDFromBytes(("task:" + eventId).getBytes(StandardCharsets.UTF_8)).toString();

        return new Task(taskId, tenantId, name, assignTo, createdBy, eventId,
                new Task.OnComplete(onCompleteAction, target), taskPayload, Task.Status.OPEN, null,
                clock.instant(), null, notification.causation());
    }

    private static String stringField(Map<String, Object> map, String key, String eventId) {
        Object v = map.get(key);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new MalformedTaskEventException(eventId, key + " is required");
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapField(Map<String, Object> map, String key, String eventId) {
        Object v = map.get(key);
        if (!(v instanceof Map<?, ?> m)) {
            throw new MalformedTaskEventException(eventId, key + " must be a map");
        }
        return (Map<String, Object>) m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castStringKeyed(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
