package com.github.ifrugal.lifecycle.api.rules;

import java.time.Duration;

/**
 * One declared output. No {@code to} means a notification; {@code to} means a signal. {@code after} makes a
 * signal a timer. {@code dispatch} overrides the default of DD-09 and then requires {@code reason}.
 */
public record EmitDocument(String action, TargetDocument to, Object payload, Duration after, Dispatch dispatch, String reason) {

    public static EmitDocument notification(String action, Object payload) {
        return new EmitDocument(action, null, payload, null, null, null);
    }

    public static EmitDocument signal(String action, TargetDocument to, Object payload) {
        return new EmitDocument(action, to, payload, null, null, null);
    }

    public boolean isSignal() {
        return to != null;
    }
}
