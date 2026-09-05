package com.github.ifrugal.lifecycle.tasks;

import com.github.ifrugal.lifecycle.api.model.EntityRef;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Storage SPI for tasks. Implementations: in-memory (this module), JDBC and Mongo (their backend modules).
 * {@link #transition} is a compare-and-set on status so two claimants or two completions cannot both win.
 */
public interface TaskStore {

    /** Idempotent on {@code taskId}: creating a task that already exists is a no-op returning false (R10). */
    boolean create(Task task);

    Optional<Task> find(String taskId);

    /**
     * Atomic: apply {@code updated} only if the stored task currently has {@code expectedStatus}.
     * @return true if applied; false if the status had moved (caller re-reads and decides)
     */
    boolean transition(String taskId, Task.Status expectedStatus, Task updated);

    /** Open or claimed tasks visible to an actor holding any of {@code roles}, oldest first. Null tenant = all tenants. */
    List<Task> openFor(String tenantId, Set<String> roles, int limit);

    List<Task> byEntity(EntityRef createdBy);
}
