package com.github.ifrugal.lifecycle.core.rules;

import java.util.Map;
import java.util.Optional;

/** Every machine the engine may use, frozen at one source fingerprint. Swapped atomically by the registry. */
public record Snapshot(String version, Map<MachineKey, Machine> machines) {

    public Snapshot {
        machines = Map.copyOf(machines);
    }

    /** Tenant overlay if present, otherwise the base machine. */
    public Optional<Machine> machine(String tenantId, String entityType) {
        if (tenantId != null) {
            Machine m = machines.get(new MachineKey(tenantId, entityType));
            if (m != null) {
                return Optional.of(m);
            }
        }
        return Optional.ofNullable(machines.get(MachineKey.base(entityType)));
    }
}
