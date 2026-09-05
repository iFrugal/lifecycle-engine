package com.github.ifrugal.lifecycle.starter;

import com.github.ifrugal.lifecycle.api.rules.Problem;
import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import com.github.ifrugal.lifecycle.core.render.MermaidRenderer;
import com.github.ifrugal.lifecycle.core.rules.CompiledTransition;
import com.github.ifrugal.lifecycle.core.rules.Machine;
import com.github.ifrugal.lifecycle.core.rules.MachineKey;
import com.github.ifrugal.lifecycle.core.rules.Snapshot;
import com.github.ifrugal.lifecycle.core.rules.StateName;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Actuator endpoint {@code lifecyclerules} (DD-13, bean 10). {@code GET} reports what is loaded and how the last
 * load went; {@code GET /{machine}} renders one machine as a Mermaid state diagram; {@code POST} reloads.
 *
 * <p>The {@code machine} selector is the {@code key} each machine reports: the entity type for a base machine,
 * {@code tenant:entityType} for a tenant overlay.
 */
@Endpoint(id = "lifecyclerules")
public class LifecycleRulesEndpoint {

    private final LifecycleRuleReloader reloader;

    public LifecycleRulesEndpoint(LifecycleRuleReloader reloader) {
        this.reloader = Objects.requireNonNull(reloader, "reloader");
    }

    @ReadOperation
    public Map<String, Object> rules() {
        DefinitionRegistry registry = reloader.registry();
        Map<String, Object> out = new LinkedHashMap<>();
        if (registry.isLoaded()) {
            Snapshot snapshot = registry.snapshot();
            out.put("version", snapshot.version());
            out.put("machines", machines(snapshot));
        } else {
            out.put("version", null);
            out.put("machines", List.of());
        }
        out.put("lastReload", lastReload());
        return out;
    }

    /** @return the Mermaid state diagram for one machine, or null when no machine has that key */
    @ReadOperation
    public String diagram(@Selector String machine) {
        DefinitionRegistry registry = reloader.registry();
        if (!registry.isLoaded()) {
            return null;
        }
        for (Map.Entry<MachineKey, Machine> entry : registry.snapshot().machines().entrySet()) {
            if (key(entry.getKey()).equals(machine)) {
                return MermaidRenderer.render(entry.getValue());
            }
        }
        return null;
    }

    /** Reloads now and reports the result; the previous snapshot stays live if the new one has problems. */
    @WriteOperation
    public Map<String, Object> reload() {
        DefinitionRegistry.ReloadResult result = reloader.reload();
        Map<String, Object> out = new LinkedHashMap<>(describe(result));
        out.put("machines", reloader.registry().isLoaded() ? machines(reloader.registry().snapshot()) : List.of());
        return out;
    }

    private Map<String, Object> lastReload() {
        return reloader.lastReload().map(LifecycleRulesEndpoint::describe).orElseGet(() -> {
            Map<String, Object> never = new LinkedHashMap<>();
            never.put("applied", false);
            never.put("version", null);
            never.put("problems", List.of());
            never.put("warnings", List.of());
            return never;
        });
    }

    private static Map<String, Object> describe(DefinitionRegistry.ReloadResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applied", result.applied());
        out.put("version", result.version());
        out.put("problems", result.problems().stream().map(Problem::toString).toList());
        out.put("warnings", List.copyOf(result.warnings()));
        return out;
    }

    private static List<Map<String, Object>> machines(Snapshot snapshot) {
        List<Map<String, Object>> out = new ArrayList<>();
        snapshot.machines().forEach((key, machine) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key(key));
            m.put("tenant", key.tenantId());
            m.put("entityType", key.entityType());
            m.put("initial", machine.initial().value());
            m.put("states", machine.states().stream().map(StateName::value).sorted().toList());
            m.put("transitions", machine.transitions().stream().map(LifecycleRulesEndpoint::transition).toList());
            out.add(m);
        });
        out.sort((a, b) -> String.valueOf(a.get("key")).compareTo(String.valueOf(b.get("key"))));
        return out;
    }

    private static Map<String, Object> transition(CompiledTransition t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", t.id());
        out.put("from", t.from().text());
        out.put("except", t.except().stream().map(StateName::value).sorted().toList());
        out.put("on", t.on());
        out.put("roles", t.roles().stream().sorted().toList());
        out.put("when", t.when());
        out.put("guard", t.guard());
        out.put("to", t.to().value());
        return out;
    }

    private static String key(MachineKey key) {
        return key.tenantId() == null ? key.entityType() : key.tenantId() + ":" + key.entityType();
    }
}
