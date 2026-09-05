package com.github.ifrugal.lifecycle.tasks;

import com.github.ifrugal.lifecycle.api.model.EntityRef;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Reference {@link TaskStore}: a synchronized map. Backend modules (JDBC, Mongo) honour the same contract. */
public final class InMemoryTaskStore implements TaskStore {

    private final Map<String, Task> tasks = new LinkedHashMap<>();

    @Override
    public synchronized boolean create(Task task) {
        if (tasks.containsKey(task.taskId())) {
            return false;
        }
        tasks.put(task.taskId(), task);
        return true;
    }

    @Override
    public synchronized Optional<Task> find(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public synchronized boolean transition(String taskId, Task.Status expectedStatus, Task updated) {
        Task current = tasks.get(taskId);
        if (current == null || current.status() != expectedStatus) {
            return false;
        }
        tasks.put(taskId, updated);
        return true;
    }

    @Override
    public synchronized List<Task> openFor(String tenantId, Set<String> roles, int limit) {
        return tasks.values().stream()
                .filter(Task::isOpen)
                .filter(t -> tenantId == null || tenantId.equals(t.tenantId()))
                .filter(t -> t.assignTo().isEmpty() || !Collections.disjoint(t.assignTo(), roles))
                .sorted(Comparator.comparing(Task::createdAt).thenComparing(Task::taskId))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized List<Task> byEntity(EntityRef createdBy) {
        return tasks.values().stream()
                .filter(t -> t.createdBy().equals(createdBy))
                .sorted(Comparator.comparing(Task::createdAt).thenComparing(Task::taskId))
                .toList();
    }
}
