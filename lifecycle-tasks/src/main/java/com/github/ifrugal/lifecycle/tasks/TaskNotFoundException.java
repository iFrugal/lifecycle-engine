package com.github.ifrugal.lifecycle.tasks;

/** No task exists for the given id. */
public final class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(String taskId) {
        super("task not found: " + taskId);
    }
}
