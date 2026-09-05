package com.github.ifrugal.lifecycle.core.rules;

import com.github.ifrugal.lifecycle.api.model.EventKind;
import com.github.ifrugal.lifecycle.api.rules.Dispatch;

import java.time.Duration;

/** A validated output declaration. {@code target} is null for notifications. */
public record CompiledEmit(String action, EventKind kind, Target target, Object payloadTemplate, Duration after, Dispatch dispatch) {

    public record Target(boolean self, String type, Object idTemplate) {}
}
