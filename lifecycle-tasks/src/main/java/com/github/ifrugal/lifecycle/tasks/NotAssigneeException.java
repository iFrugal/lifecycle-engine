package com.github.ifrugal.lifecycle.tasks;

/** The actor holds none of the task's {@code assignTo} roles (empty {@code assignTo} means anyone may act). */
public final class NotAssigneeException extends RuntimeException {

    public NotAssigneeException(String taskId, String actorId) {
        super("actor " + actorId + " does not hold an assignee role for task " + taskId);
    }
}
