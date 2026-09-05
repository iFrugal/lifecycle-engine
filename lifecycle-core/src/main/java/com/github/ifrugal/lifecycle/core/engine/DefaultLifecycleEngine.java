package com.github.ifrugal.lifecycle.core.engine;

import com.github.ifrugal.lifecycle.api.LifecycleEngine;
import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.AuditOutcome;
import com.github.ifrugal.lifecycle.api.model.AuditRecord;
import com.github.ifrugal.lifecycle.api.model.Decision;
import com.github.ifrugal.lifecycle.api.model.Emission;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.Outcome;
import com.github.ifrugal.lifecycle.api.model.RefusalReason;
import com.github.ifrugal.lifecycle.api.model.StateRecord;
import com.github.ifrugal.lifecycle.api.model.TransitionView;
import com.github.ifrugal.lifecycle.api.rules.Dispatch;
import com.github.ifrugal.lifecycle.api.spi.Commit;
import com.github.ifrugal.lifecycle.api.spi.CommitResult;
import com.github.ifrugal.lifecycle.api.spi.StateStore;
import com.github.ifrugal.lifecycle.api.spi.Transport;
import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import com.github.ifrugal.lifecycle.core.rules.GuardRegistry;
import com.github.ifrugal.lifecycle.core.rules.Machine;
import com.github.ifrugal.lifecycle.core.rules.Snapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Read → decide (pure) → commit (atomic) → publish (DD-07). {@link #evaluate} and {@link #available} stop after
 * decide and can therefore have no consequences (H3). Every outcome, including every refusal, is committed to the
 * audit (H9); conflicts are audited detached (R12).
 */
public final class DefaultLifecycleEngine implements LifecycleEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultLifecycleEngine.class);

    private final DefinitionRegistry registry;
    private final StateStore store;
    private final Transport transport;
    private final TransitionResolver resolver;
    private final EngineConfig config;

    public DefaultLifecycleEngine(DefinitionRegistry registry, StateStore store, Transport transport, GuardRegistry guards) {
        this(registry, store, transport, guards, EngineConfig.defaults());
    }

    public DefaultLifecycleEngine(DefinitionRegistry registry, StateStore store, Transport transport, GuardRegistry guards, EngineConfig config) {
        this.registry = registry;
        this.store = store;
        this.transport = transport;
        this.config = config;
        this.resolver = new TransitionResolver(guards, config.engineActor(), config.clock());
        if (registry.isLoaded() && registry.requiresDelay() && !transport.supportsDelay()) {
            throw new IllegalStateException("loaded rules declare `after` (timers) but the transport does not support delayed delivery");
        }
    }

    @Override
    public Outcome handle(LifecycleEvent event) {
        Set<String> inlineVisited = new HashSet<>();
        inlineVisited.add(visitKey(event));
        return handle(event, inlineVisited);
    }

    @Override
    public Decision evaluate(LifecycleEvent event) {
        Snapshot snap = registry.snapshot();
        Optional<Machine> machine = snap.machine(event.entity().tenantId(), event.entity().type());
        if (machine.isEmpty()) {
            return new Decision.Refuse(RefusalReason.NO_MACHINE, noMachineDetail(event.entity()));
        }
        StateRecord record = store.find(event.entity()).orElse(StateRecord.initial(event.entity(), machine.get().initial().value()));
        return resolver.decide(machine.get(), record, event);
    }

    @Override
    public List<TransitionView> available(EntityRef ref, Actor actor) {
        Optional<Machine> machine = registry.snapshot().machine(ref.tenantId(), ref.type());
        if (machine.isEmpty()) {
            return List.of();
        }
        StateRecord record = store.find(ref).orElse(StateRecord.initial(ref, machine.get().initial().value()));
        return resolver.available(machine.get(), record, actor);
    }

    private Outcome handle(LifecycleEvent event, Set<String> inlineVisited) {
        Snapshot snap = registry.snapshot();
        Optional<Machine> machineOpt = snap.machine(event.entity().tenantId(), event.entity().type());
        StateRecord existing = store.find(event.entity()).orElse(null);

        if (machineOpt.isEmpty()) {
            return commitRefusal(event, existing, snap.version(), RefusalReason.NO_MACHINE, noMachineDetail(event.entity()));
        }
        Machine machine = machineOpt.get();
        StateRecord record = existing != null ? existing : StateRecord.initial(event.entity(), machine.initial().value());

        if (event.expectedVersion() != null && event.expectedVersion() != record.version()) {
            store.appendDetached(audit(event, record.state(), null, null, AuditOutcome.CONFLICTED, null,
                    "caller expected version " + event.expectedVersion() + " but the entity is at " + record.version(), snap.version()));
            return new Outcome.Conflicted(event.expectedVersion(), record.version());
        }

        Decision decision = resolver.decide(machine, record, event);
        return switch (decision) {
            case Decision.Refuse r -> commitRefusal(event, record, snap.version(), r.reason(), r.detail());
            case Decision.Match m -> apply(event, record, snap.version(), m, inlineVisited);
        };
    }

    private Outcome apply(LifecycleEvent event, StateRecord record, String ruleSetVersion, Decision.Match match, Set<String> inlineVisited) {
        List<LifecycleEvent> emitted = match.emissions().stream().map(Emission::event).toList();
        AuditRecord audit = audit(event, record.state(), match.to(), match.transition().id(), AuditOutcome.APPLIED, null, null, ruleSetVersion);
        CommitResult result = store.commit(new Commit(event.entity(), record.version(), match.to(), event.eventId(), audit, emitted));

        return switch (result) {
            case CommitResult.AlreadyApplied a -> new Outcome.Duplicate(a.firstAuditId());
            case CommitResult.VersionMismatch vm -> conflicted(event, record, ruleSetVersion, vm);
            case CommitResult.Committed c -> {
                dispatch(match.emissions(), inlineVisited);
                yield new Outcome.Applied(match.transition().id(), match.from(), match.to(), c.version(), emitted);
            }
        };
    }

    /** After commit only. Inline signals re-enter here synchronously; everything else goes to the transport (DD-09). */
    private void dispatch(List<Emission> emissions, Set<String> inlineVisited) {
        List<String> handled = new ArrayList<>();
        for (Emission em : emissions) {
            LifecycleEvent e = em.event();
            if (e.isSignal() && em.dispatch() == Dispatch.INLINE) {
                if (!inlineVisited.add(visitKey(e))) {
                    Snapshot snap = registry.snapshot();
                    commitRefusal(e, store.find(e.entity()).orElse(null), snap.version(), RefusalReason.CYCLE,
                            "inline cascade revisited " + e.entity().key() + " on " + e.action() + " (correlation " + e.causation().correlationId() + ")");
                } else {
                    handle(e, inlineVisited);
                }
            } else {
                transport.publish(e);
            }
            handled.add(e.eventId());
        }
        if (!handled.isEmpty()) {
            store.outbox().ifPresent(o -> o.markSent(handled));
        }
    }

    private Outcome commitRefusal(LifecycleEvent event, StateRecord record, String ruleSetVersion, RefusalReason reason, String detail) {
        String from = record == null ? null : record.state();
        long version = record == null ? 0L : record.version();
        AuditRecord audit = audit(event, from, null, null, AuditOutcome.REFUSED, reason, detail, ruleSetVersion);
        CommitResult result = store.commit(new Commit(event.entity(), version, null, event.eventId(), audit, List.of()));
        return switch (result) {
            case CommitResult.Committed c -> {
                log.debug("refused {} on {}: {} {}", event.action(), event.entity().key(), reason, detail);
                yield new Outcome.Refused(reason, detail);
            }
            case CommitResult.AlreadyApplied a -> new Outcome.Duplicate(a.firstAuditId());
            case CommitResult.VersionMismatch vm -> conflicted(event, record, ruleSetVersion, vm);
        };
    }

    private Outcome conflicted(LifecycleEvent event, StateRecord record, String ruleSetVersion, CommitResult.VersionMismatch vm) {
        store.appendDetached(audit(event, record == null ? null : record.state(), null, null, AuditOutcome.CONFLICTED, null,
                "expected version " + vm.expected() + " but the entity is at " + vm.actual(), ruleSetVersion));
        return new Outcome.Conflicted(vm.expected(), vm.actual());
    }

    private AuditRecord audit(LifecycleEvent e, String from, String to, String transitionId, AuditOutcome outcome,
                              RefusalReason reason, String detail, String ruleSetVersion) {
        return new AuditRecord(config.auditIds().get(), e.eventId(), e.entity(), e.action(), e.actor(), from, to, transitionId,
                outcome, reason, detail, ruleSetVersion, config.clock().instant(), e.causation());
    }

    private static String visitKey(LifecycleEvent e) {
        return e.entity().key() + "|" + e.action();
    }

    private static String noMachineDetail(EntityRef ref) {
        return "no machine for entity type '" + ref.type() + "'" + (ref.tenantId() == null ? "" : " (tenant " + ref.tenantId() + ", base also absent)");
    }
}
