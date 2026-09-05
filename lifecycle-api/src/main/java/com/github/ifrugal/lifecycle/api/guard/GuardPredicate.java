package com.github.ifrugal.lifecycle.api.guard;

/**
 * The D1 escape hatch: a guard declared in data by name and implemented in code. Registered at startup; a rule
 * set naming an unregistered guard refuses to load. Must be pure and fast: it runs on the dry-run path too.
 */
public interface GuardPredicate {

    /** The name rules refer to, e.g. {@code refund-window-open}. */
    String name();

    boolean test(GuardContext context);
}
