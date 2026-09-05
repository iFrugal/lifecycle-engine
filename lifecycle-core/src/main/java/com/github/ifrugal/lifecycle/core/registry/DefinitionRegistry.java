package com.github.ifrugal.lifecycle.core.registry;

import com.github.ifrugal.lifecycle.api.model.EventKind;
import com.github.ifrugal.lifecycle.api.rules.Problem;
import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;
import com.github.ifrugal.lifecycle.api.rules.RuleSetValidationException;
import com.github.ifrugal.lifecycle.api.spi.DefinitionSource;
import com.github.ifrugal.lifecycle.api.spi.StateStore;
import com.github.ifrugal.lifecycle.core.rules.CompiledEmit;
import com.github.ifrugal.lifecycle.core.rules.CompiledTransition;
import com.github.ifrugal.lifecycle.core.rules.GuardRegistry;
import com.github.ifrugal.lifecycle.core.rules.Machine;
import com.github.ifrugal.lifecycle.core.rules.MachineKey;
import com.github.ifrugal.lifecycle.core.rules.OverlayMerger;
import com.github.ifrugal.lifecycle.core.rules.RuleCompiler;
import com.github.ifrugal.lifecycle.core.rules.Snapshot;
import com.github.ifrugal.lifecycle.core.rules.StateName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loads documents from a {@link DefinitionSource}, merges tenant overlays, compiles and validates every machine,
 * checks for orphaned entities, and swaps an immutable {@link Snapshot} atomically (DD-05, DD-06). A failed
 * reload leaves the previous snapshot live.
 */
public final class DefinitionRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefinitionRegistry.class);

    private final DefinitionSource source;
    private final GuardRegistry guards;
    private final StateStore orphanStore;
    private final RuleCompiler compiler = new RuleCompiler();
    private final AtomicReference<Snapshot> current = new AtomicReference<>();

    public record ReloadResult(boolean applied, String version, List<Problem> problems, List<String> warnings) {}

    public DefinitionRegistry(DefinitionSource source, GuardRegistry guards) {
        this(source, guards, null);
    }

    /** @param orphanStore consulted for {@code countInState} when a reload removes states; may be null */
    public DefinitionRegistry(DefinitionSource source, GuardRegistry guards, StateStore orphanStore) {
        this.source = source;
        this.guards = guards;
        this.orphanStore = orphanStore;
    }

    public boolean isLoaded() {
        return current.get() != null;
    }

    public Snapshot snapshot() {
        Snapshot s = current.get();
        if (s == null) {
            throw new IllegalStateException("no rule snapshot loaded; call reload() first");
        }
        return s;
    }

    public Optional<Machine> machine(String tenantId, String entityType) {
        return snapshot().machine(tenantId, entityType);
    }

    /** True if any loaded rule declares {@code after}; a transport without delay support must then be refused at startup. */
    public boolean requiresDelay() {
        return snapshot().machines().values().stream()
                .flatMap(m -> m.transitions().stream())
                .flatMap(t -> t.emit().stream())
                .anyMatch(e -> e.after() != null);
    }

    public ReloadResult reloadOrThrow() {
        ReloadResult r = reload();
        if (!r.applied()) {
            throw new RuleSetValidationException(r.problems());
        }
        return r;
    }

    public ReloadResult reload() {
        List<RuleSetDocument> docs = List.copyOf(source.load());
        String version = source.fingerprint();
        List<Problem> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Map<String, RuleSetDocument> bases = new LinkedHashMap<>();
        List<RuleSetDocument> overlays = new ArrayList<>();
        for (RuleSetDocument d : docs) {
            if (d.isOverlay()) {
                overlays.add(d);
            } else if (bases.put(d.entityType(), d) != null) {
                problems.add(new Problem(null, d.entityType(), null, "more than one base rule set for this entity type"));
            }
        }
        Set<String> knownTypes = bases.keySet();

        Map<MachineKey, Machine> machines = new LinkedHashMap<>();
        for (RuleSetDocument base : bases.values()) {
            RuleCompiler.Result r = compiler.compile(base, guards, knownTypes);
            problems.addAll(r.problems());
            warnings.addAll(r.warnings());
            if (r.ok()) {
                machines.put(MachineKey.base(base.entityType()), r.machine());
            }
        }
        Set<MachineKey> seenOverlays = new HashSet<>();
        for (RuleSetDocument overlay : overlays) {
            MachineKey key = new MachineKey(overlay.tenantId(), overlay.entityType());
            if (!seenOverlays.add(key)) {
                problems.add(new Problem(overlay.tenantId(), overlay.entityType(), null, "more than one overlay for this tenant and entity type"));
                continue;
            }
            RuleSetDocument base = bases.get(overlay.entityType());
            if (base == null) {
                problems.add(new Problem(overlay.tenantId(), overlay.entityType(), null, "overlay has no base rule set"));
                continue;
            }
            OverlayMerger.Result merged = OverlayMerger.merge(base, overlay);
            problems.addAll(merged.problems());
            if (!merged.ok()) {
                continue;
            }
            RuleCompiler.Result r = compiler.compile(merged.merged(), guards, knownTypes);
            problems.addAll(r.problems());
            warnings.addAll(r.warnings());
            if (r.ok()) {
                machines.put(key, r.machine());
            }
        }

        if (problems.isEmpty()) {
            Snapshot candidate = new Snapshot(version, machines);
            warnUnconsumedSignals(candidate, warnings);
            checkOrphans(current.get(), candidate, problems, warnings);
        }

        if (!problems.isEmpty()) {
            log.error("rule reload rejected ({} problems); previous snapshot stays live", problems.size());
            problems.forEach(p -> log.error("  {}", p));
            return new ReloadResult(false, version, List.copyOf(problems), List.copyOf(warnings));
        }
        current.set(new Snapshot(version, machines));
        warnings.forEach(w -> log.warn("rule warning: {}", w));
        log.info("rule snapshot {} active: {} machine(s)", version, machines.size());
        return new ReloadResult(true, version, List.of(), List.copyOf(warnings));
    }

    private static void warnUnconsumedSignals(Snapshot snap, List<String> warnings) {
        for (Machine m : snap.machines().values()) {
            for (CompiledTransition t : m.transitions()) {
                for (CompiledEmit e : t.emit()) {
                    if (e.kind() != EventKind.SIGNAL) {
                        continue;
                    }
                    String targetType = e.target().self() ? m.entityType() : e.target().type();
                    Optional<Machine> target = snap.machine(m.tenantId(), targetType);
                    if (target.isPresent() && target.get().forAction(e.action()).isEmpty()) {
                        warnings.add(m.entityType() + " / " + t.id() + ": signal " + e.action() + " is consumed by no transition of " + targetType);
                    }
                }
            }
        }
    }

    /** States (or whole machines) that disappear while entities still sit in them fail the reload (DD-06). */
    private void checkOrphans(Snapshot previous, Snapshot candidate, List<Problem> problems, List<String> warnings) {
        if (previous == null) {
            return;
        }
        for (Map.Entry<MachineKey, Machine> e : previous.machines().entrySet()) {
            MachineKey key = e.getKey();
            Machine old = e.getValue();
            Optional<Machine> replacement = candidate.machine(key.tenantId(), key.entityType());
            Set<StateName> remaining = replacement.map(Machine::states).orElse(Set.of());
            for (StateName s : old.states()) {
                if (remaining.contains(s)) {
                    continue;
                }
                if (orphanStore == null) {
                    warnings.add(key.entityType() + ": state " + s + " removed; no store available to check for entities still in it");
                    continue;
                }
                OptionalLong count = orphanStore.countInState(key.tenantId(), key.entityType(), s.value());
                if (count.isEmpty()) {
                    warnings.add(key.entityType() + ": state " + s + " removed; store cannot count entities still in it");
                } else if (count.getAsLong() > 0) {
                    problems.add(new Problem(key.tenantId(), key.entityType(), null,
                            "state " + s + " removed but " + count.getAsLong() + " entit(y/ies) still sit in it; add a migration edge first"));
                }
            }
        }
    }
}
