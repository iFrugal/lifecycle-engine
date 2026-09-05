package com.github.ifrugal.lifecycle.api.model;

/** Why an event did not move an entity. Listed in evaluation order; the audit carries the first gate that stopped it. */
public enum RefusalReason {
    /** No machine for (tenant, entity type). */
    NO_MACHINE,
    /** Hop count exceeded the rule set's maxHops (H6). */
    HOP_LIMIT,
    /** An inline chain revisited (entity, action) (H6). */
    CYCLE,
    /** No transition from the current state on this action. */
    NO_MATCH,
    /** Transitions exist but the actor holds none of the required roles. */
    ROLE_DENIED,
    /** Payload conditions or named guard not met. */
    GUARD_FAILED,
    /** More than one transition matched after all guards. Should be unreachable; a compiler bug if reached. */
    AMBIGUOUS
}
