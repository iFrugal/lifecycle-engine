package com.github.ifrugal.lifecycle.api.model;

/**
 * SIGNAL: addressed to the engine and may move an entity. NOTIFICATION: for the outside world; the engine never
 * consumes it. Set by the emitter, never inferred from the action name.
 */
public enum EventKind { SIGNAL, NOTIFICATION }
