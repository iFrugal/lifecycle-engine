package com.github.ifrugal.lifecycle.core.rules;

import com.github.ifrugal.lifecycle.api.model.EventKind;
import com.github.ifrugal.lifecycle.api.rules.Dispatch;
import com.github.ifrugal.lifecycle.api.rules.EmitDocument;
import com.github.ifrugal.lifecycle.api.rules.Problem;
import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;
import com.github.ifrugal.lifecycle.api.rules.RuleSetValidationException;
import com.github.ifrugal.lifecycle.api.rules.TargetDocument;
import com.github.ifrugal.lifecycle.api.rules.TaskDocument;
import com.github.ifrugal.lifecycle.api.rules.TransitionDocument;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns one (already merged) {@link RuleSetDocument} into an immutable {@link Machine}, or a list of every
 * problem found (DD-04). Never throws for rule content; {@link #compileOrThrow} wraps for convenience.
 */
public final class RuleCompiler {

    /** The only action vocabulary the core owns (DD-10, H5). */
    public static final String RESERVED_PREFIX = "lifecycle.";
    public static final String TASK_CREATE_ACTION = RESERVED_PREFIX + "task.create";
    public static final int DEFAULT_MAX_HOPS = 32;

    public record Result(Machine machine, List<Problem> problems, List<String> warnings) {
        public boolean ok() {
            return machine != null;
        }
    }

    public Result compile(RuleSetDocument doc, GuardRegistry guards, Set<String> knownEntityTypes) {
        Collector c = new Collector(doc);

        if (doc.entityType().isBlank()) {
            c.problem(null, "entityType must not be blank");
        }

        Set<StateName> states = new LinkedHashSet<>();
        for (String s : doc.states()) {
            if (s == null || s.isBlank()) {
                c.problem(null, "blank state name");
                continue;
            }
            if (!states.add(StateName.of(s))) {
                c.problem(null, "duplicate state " + s);
            }
        }
        if (states.isEmpty()) {
            c.problem(null, "at least one state is required");
        }

        StateName initial = null;
        if (doc.initial() == null || doc.initial().isBlank()) {
            c.problem(null, "initial is required");
        } else {
            initial = StateName.of(doc.initial());
            if (!states.contains(initial)) {
                c.problem(null, "initial state " + initial + " is not declared in states");
            }
        }

        int maxHops = doc.maxHops() == null ? DEFAULT_MAX_HOPS : doc.maxHops();
        if (maxHops < 1) {
            c.problem(null, "maxHops must be >= 1");
        }

        List<CompiledTransition> compiled = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int index = 0;
        for (TransitionDocument t : doc.transitions()) {
            index++;
            String tid = t.id() == null || t.id().isBlank() ? "#" + index : t.id();
            if (t.id() == null || t.id().isBlank()) {
                c.problem(tid, "transition id is required");
            } else if (!ids.add(t.id())) {
                c.problem(tid, "duplicate transition id");
            }
            if (t.disabled()) {
                c.warn(tid, "disabled transition in a non-overlay document is ignored");
                continue;
            }
            CompiledTransition ct = compileTransition(t, tid, states, guards, knownEntityTypes, c);
            if (ct != null) {
                compiled.add(ct);
            }
        }

        checkAmbiguity(compiled, states, c);
        warnUnreachable(compiled, states, initial, c);

        if (!c.problems.isEmpty()) {
            return new Result(null, List.copyOf(c.problems), List.copyOf(c.warnings));
        }
        Machine m = Machine.of(doc.tenantId(), doc.entityType(), initial, maxHops, states, compiled);
        return new Result(m, List.of(), List.copyOf(c.warnings));
    }

    public Machine compileOrThrow(RuleSetDocument doc, GuardRegistry guards, Set<String> knownEntityTypes) {
        Result r = compile(doc, guards, knownEntityTypes);
        if (!r.ok()) {
            throw new RuleSetValidationException(r.problems());
        }
        return r.machine();
    }

    private CompiledTransition compileTransition(TransitionDocument t, String tid, Set<StateName> states, GuardRegistry guards,
                                                 Set<String> knownTypes, Collector c) {
        int before = c.problems.size();

        StatePattern from = null;
        if (t.from() == null || t.from().isBlank()) {
            c.problem(tid, "from is required");
        } else {
            from = StatePattern.parse(t.from());
            if (from instanceof StatePattern.Exact e && !states.contains(e.state())) {
                c.problem(tid, "from state " + e.state() + " is not declared");
            }
            if (from instanceof StatePattern.Prefix p && states.stream().noneMatch(s -> s.under(p.prefix()))) {
                c.warn(tid, "from pattern " + p.text() + " matches no declared state");
            }
        }

        Set<StateName> except = new LinkedHashSet<>();
        for (String x : t.except()) {
            StateName sn = StateName.of(x);
            if (!states.contains(sn)) {
                c.problem(tid, "except state " + x + " is not declared");
            }
            except.add(sn);
        }

        if (t.on() == null || t.on().isBlank()) {
            c.problem(tid, "on is required");
        }

        StateName to = null;
        if (t.to() == null || t.to().isBlank()) {
            c.problem(tid, "to is required");
        } else {
            to = StateName.of(t.to());
            if (!states.contains(to)) {
                c.problem(tid, "to state " + to + " is not declared");
            }
        }

        if (t.guard() != null && guards.find(t.guard()).isEmpty()) {
            c.problem(tid, "guard '" + t.guard() + "' is not registered; known guards: " + guards.names());
        }

        List<CompiledEmit> emits = new ArrayList<>();
        int ei = 0;
        for (EmitDocument e : t.emit()) {
            ei++;
            CompiledEmit ce = compileEmit(e, tid, "emit[" + ei + "]", knownTypes, c);
            if (ce != null) {
                emits.add(ce);
            }
        }
        if (t.task() != null) {
            CompiledEmit taskEmit = compileTask(t.task(), tid, knownTypes, c);
            if (taskEmit != null) {
                emits.add(taskEmit);
            }
        }

        if (c.problems.size() > before) {
            return null;
        }
        return new CompiledTransition(t.id(), from, except, t.on(), t.roles(), t.when(), t.guard(), to, emits, t.task());
    }

    private CompiledEmit compileEmit(EmitDocument e, String tid, String where, Set<String> knownTypes, Collector c) {
        int before = c.problems.size();
        if (e.action() == null || e.action().isBlank()) {
            c.problem(tid, where + ": action is required");
        }
        CompiledEmit.Target target = null;
        if (e.isSignal()) {
            target = compileTarget(e.to(), tid, where, knownTypes, c);
        } else if (e.after() != null) {
            c.problem(tid, where + ": after is only meaningful for a signal (an emit with `to`)");
        }
        // DD-09: same-entity signals run inline by default, everything else over the transport. A timer (after) is a
        // delayed signal and can only be honoured by a transport, so it is never inline by default.
        Dispatch defaultDispatch = (e.isSignal() && e.after() == null && e.to().self()) ? Dispatch.INLINE : Dispatch.TRANSPORT;
        Dispatch dispatch = e.dispatch() == null ? defaultDispatch : e.dispatch();
        if (!e.isSignal() && dispatch == Dispatch.INLINE) {
            c.problem(tid, where + ": a notification always goes over the transport");
        } else if (e.after() != null && dispatch == Dispatch.INLINE) {
            c.problem(tid, where + ": a timer (after) cannot be dispatched inline; only a transport can delay it");
        } else if (e.dispatch() != null && e.dispatch() != defaultDispatch && (e.reason() == null || e.reason().isBlank())) {
            c.problem(tid, where + ": dispatch override requires a reason");
        }
        List<String> bad = Projection.invalidReferences(e.payload());
        if (!bad.isEmpty()) {
            c.problem(tid, where + ": unknown references " + bad);
        }
        if (c.problems.size() > before) {
            return null;
        }
        EventKind kind = e.isSignal() ? EventKind.SIGNAL : EventKind.NOTIFICATION;
        return new CompiledEmit(e.action(), kind, target, e.payload(), e.after(), dispatch);
    }

    private CompiledEmit.Target compileTarget(TargetDocument to, String tid, String where, Set<String> knownTypes, Collector c) {
        if (to.self()) {
            return new CompiledEmit.Target(true, null, null);
        }
        if (to.type() == null || to.type().isBlank()) {
            c.problem(tid, where + ": target type is required");
        } else if (!knownTypes.contains(to.type())) {
            c.problem(tid, where + ": target entity type '" + to.type() + "' has no machine; known: " + knownTypes);
        }
        if (to.id() == null) {
            c.problem(tid, where + ": target id is required");
        } else {
            List<String> bad = Projection.invalidReferences(to.id());
            if (!bad.isEmpty()) {
                c.problem(tid, where + ": unknown references in target id " + bad);
            }
        }
        return new CompiledEmit.Target(false, to.type(), to.id());
    }

    /** A task compiles to a {@code lifecycle.task.create} notification whose payload carries the deferred signal (DD-10). */
    private CompiledEmit compileTask(TaskDocument task, String tid, Set<String> knownTypes, Collector c) {
        int before = c.problems.size();
        if (task.name() == null || task.name().isBlank()) {
            c.problem(tid, "task: name is required");
        }
        if (task.onCompleteAction() == null || task.onCompleteAction().isBlank()) {
            c.problem(tid, "task: onComplete.action is required");
        }
        TargetDocument target = task.onCompleteTarget() == null ? TargetDocument.toSelf() : task.onCompleteTarget();
        CompiledEmit.Target ct = compileTarget(target, tid, "task.onComplete", knownTypes, c);
        List<String> bad = Projection.invalidReferences(task.payload());
        if (!bad.isEmpty()) {
            c.problem(tid, "task: unknown references " + bad);
        }
        if (c.problems.size() > before) {
            return null;
        }
        Map<String, Object> onCompleteTarget = new LinkedHashMap<>();
        onCompleteTarget.put("type", ct.self() ? Projection.ENTITY_TYPE : ct.type());
        onCompleteTarget.put("id", ct.self() ? Projection.ENTITY_ID : ct.idTemplate());
        Map<String, Object> onComplete = new LinkedHashMap<>();
        onComplete.put("action", task.onCompleteAction());
        onComplete.put("target", onCompleteTarget);
        Map<String, Object> createdBy = new LinkedHashMap<>();
        createdBy.put("type", Projection.ENTITY_TYPE);
        createdBy.put("id", Projection.ENTITY_ID);
        createdBy.put("tenant", Projection.TENANT);
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("name", task.name());
        template.put("assignTo", new ArrayList<>(task.assignTo()));
        template.put("createdBy", createdBy);
        template.put("onComplete", onComplete);
        template.put("payload", task.payload() == null ? Map.of() : task.payload());
        return new CompiledEmit(TASK_CREATE_ACTION, EventKind.NOTIFICATION, null, template, null, Dispatch.TRANSPORT);
    }

    /** Two edges for the same action that can match the same state at the same specificity must be provably disjoint (DD-03). */
    private void checkAmbiguity(List<CompiledTransition> ts, Set<StateName> states, Collector c) {
        for (int i = 0; i < ts.size(); i++) {
            for (int j = i + 1; j < ts.size(); j++) {
                CompiledTransition a = ts.get(i);
                CompiledTransition b = ts.get(j);
                if (!a.on().equals(b.on()) || a.specificity() != b.specificity()) {
                    continue;
                }
                boolean overlap = states.stream().anyMatch(s -> a.appliesTo(s) && b.appliesTo(s));
                if (overlap && !WhenMatcher.disjoint(a.when(), b.when())) {
                    c.problem(a.id(), "ambiguous with '" + b.id() + "': same action, same specificity, `when` not provably disjoint (roles and guard do not disambiguate)");
                }
            }
        }
    }

    private void warnUnreachable(List<CompiledTransition> ts, Set<StateName> states, StateName initial, Collector c) {
        Set<StateName> entered = new HashSet<>();
        for (CompiledTransition t : ts) {
            entered.add(t.to());
        }
        for (StateName s : states) {
            if (!s.equals(initial) && !entered.contains(s)) {
                c.warn(null, "state " + s + " is never entered by any transition");
            }
        }
    }

    private static final class Collector {
        final RuleSetDocument doc;
        final List<Problem> problems = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        Collector(RuleSetDocument doc) {
            this.doc = doc;
        }

        void problem(String tid, String msg) {
            problems.add(new Problem(doc.tenantId(), doc.entityType(), tid, msg));
        }

        void warn(String tid, String msg) {
            warnings.add(new Problem(doc.tenantId(), doc.entityType(), tid, msg).toString());
        }
    }
}
