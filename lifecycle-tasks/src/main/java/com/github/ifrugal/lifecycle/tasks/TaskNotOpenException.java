package com.github.ifrugal.lifecycle.tasks;

/**
 * A compare-and-set against a task's status failed: the task was not in the expected status (it moved under a
 * concurrent claim/complete/cancel/release, or was never there to begin with).
 */
public final class TaskNotOpenException extends RuntimeException {

    public TaskNotOpenException(String taskId, Task.Status expected, Task.Status actual) {
        super("task " + taskId + " expected status " + expected + " but was " + actual);
    }
}
