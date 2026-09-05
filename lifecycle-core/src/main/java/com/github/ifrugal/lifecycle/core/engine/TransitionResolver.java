package com.github.ifrugal.lifecycle.core.engine;

import com.github.ifrugal.lifecycle.api.guard.GuardContext;
import com.github.ifrugal.lifecycle.api.guard.GuardPredicate;
import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.Decision;
import com.github.ifrugal.lifecycle.api.model.Emission;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.RefusalReason;
import com.github.ifrugal.lifecycle.api.model.StateRecord;
import com.github.ifrugal.lifecycle.api.model.TransitionView;
import com.github.ifrugal.lifecycle.core.rules.CompiledEmit;
import com.github.ifrugal.lifecycle.core.rules.CompiledTransition;
import com.github.ifrugal.lifecycle.core.rules.GuardRegistry;
import com.github.ifrugal.lifecycle.core.rules.Machine;
import com.github.ifrugal.lifecycle.core.rules.Projection;
import com.github.ifrugal.lifecycle.core.rules.StateName;
import com.github.ifrugal.lifecycle.core.rules.WhenMatcher;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The brief's function of (state, event, definition) → (next state, outputs). Pure: it reads its arguments and
 * the guard registry and touches nothing else (H3; enforced by architecture test). Refusal reasons are produced in
 * gate order: NO_MATCH → ROLE_DENIED → GUARD_FAILED → AMBIGUOUS (DD-03).
 */
public final class TransitionResolver {

    private final GuardRegistry guards;
    private final Actor engineActor;
    private final Clock clock;

    public TransitionResolver(GuardRegistry guards, Actor engineActor, Clock clock) {
        this.guards = guards;
        this.engineActor = engineActor;
        this.clock = clock;
    }

    public Decision decide(Machine machine, StateRecord current, LifecycleEvent event) {
        if (event.causation().hop() > machine.maxHops()) {
            return new Decision.Refuse(RefusalReason.HOP_LIMIT,
                    "hop " + event.causation().hop() + " exceeds maxHops " + machine.maxHops() + " (correlation " + event.causation().correlationId() + ")");
        }
        StateName state = StateName.of(current.state());

        List<CompiledTransition> byState = machine.forAction(event.action()).stream().filter(t -> t.appliesTo(state)).toList();
        if (byState.isEmpty()) {
            return new Decision.Refuse(RefusalReason.NO_MATCH, "no transition from state " + state + " on action " + event.action());
        }

        List<CompiledTransition> byRole = byState.stream().filter(t -> rolesAllow(t, event.actor())).toList();
        if (byRole.isEmpty()) {
            return new Decision.Refuse(RefusalReason.ROLE_DENIED,
                    "transitions " + ids(byState) + " require any of " + byState.stream().map(CompiledTransition::roles).toList()
                            + "; actor " + event.actor().id() + " has " + event.actor().roles());
        }

        List<CompiledTransition> byGuard = byRole.stream().filter(t -> guardsHold(t, current, event)).toList();
        if (byGuard.isEmpty()) {
            return new Decision.Refuse(RefusalReason.GUARD_FAILED, "conditions of " + ids(byRole) + " not met by the payload or guard");
        }

        int top = byGuard.stream().mapToInt(CompiledTransition::specificity).max().orElseThrow();
        List<CompiledTransition> winners = byGuard.stream().filter(t -> t.specificity() == top).toList();
        if (winners.size() > 1) {
            return new Decision.Refuse(RefusalReason.AMBIGUOUS, "transitions " + ids(winners) + " all match; the compiler should have refused this rule set");
        }

        CompiledTransition t = winners.get(0);
        List<Emission> emissions = new ArrayList<>();
        int i = 0;
        for (CompiledEmit e : t.emit()) {
            Projection.Context ctx = new Projection.Context(event.entity(), event.payload(), event.actor(), event.eventId(), state.value(), t.to().value());
            EntityRef target = event.entity();
            if (e.target() != null && !e.target().self()) {
                Object id = Projection.project(e.target().idTemplate(), ctx);
                if (id == null) {
                    return new Decision.Refuse(RefusalReason.GUARD_FAILED,
                            "transition " + t.id() + " emits " + e.action() + " to " + e.target().type() + " but its id " + e.target().idTemplate() + " resolved to nothing");
                }
                target = new EntityRef(event.entity().tenantId(), e.target().type(), String.valueOf(id));
            }
            Map<String, Object> payload = Projection.projectPayload(e.payloadTemplate(), ctx);
            Instant at = clock.instant();
            LifecycleEvent emitted = new LifecycleEvent(
                    emittedId(event.eventId(), i++), e.kind(), target, e.action(), engineActor, payload, at,
                    e.after() == null ? null : at.plus(e.after()), event.causation().child(event.eventId()), null);
            emissions.add(new Emission(emitted, e.dispatch()));
        }
        return new Decision.Match(t.view(), state.value(), t.to().value(), emissions);
    }

    /** What this actor could attempt from the current state, ignoring payload conditions and named guards. */
    public List<TransitionView> available(Machine machine, StateRecord current, Actor actor) {
        StateName state = StateName.of(current.state());
        return machine.transitions().stream()
                .filter(t -> t.appliesTo(state) && rolesAllow(t, actor))
                .map(CompiledTransition::view)
                .toList();
    }

    /** Deterministic, so a replayed parent re-emits identical children and downstream dedupe holds (DD-09). */
    public static String emittedId(String parentEventId, int index) {
        return UUID.nameUUIDFromBytes((parentEventId + "#" + index).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static boolean rolesAllow(CompiledTransition t, Actor actor) {
        return t.roles().isEmpty() || !Collections.disjoint(t.roles(), actor.roles());
    }

    private boolean guardsHold(CompiledTransition t, StateRecord current, LifecycleEvent event) {
        boolean when = WhenMatcher.holds(t.when(), event.payload());
        boolean named = true;
        if (t.guard() != null) {
            GuardPredicate g = guards.find(t.guard()).orElseThrow(() -> new IllegalStateException("guard " + t.guard() + " vanished after load"));
            named = g.test(new GuardContext(current, event));
        }
        return when & named;
    }

    private static List<String> ids(List<CompiledTransition> ts) {
        return ts.stream().map(CompiledTransition::id).toList();
    }
}
