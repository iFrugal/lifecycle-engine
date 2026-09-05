package com.github.ifrugal.lifecycle.tasks;

/**
 * A {@code lifecycle.task.create} notification did not carry the payload shape the compiler produces
 * (DD-10): {@code name}, {@code assignTo}, {@code createdBy}, {@code onComplete}, {@code payload}.
 */
public final class MalformedTaskEventException extends RuntimeException {

    public MalformedTaskEventException(String eventId, String reason) {
        super("malformed lifecycle.task.create notification " + eventId + ": " + reason);
    }
}
