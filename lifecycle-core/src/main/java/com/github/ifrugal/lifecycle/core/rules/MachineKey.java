package com.github.ifrugal.lifecycle.core.rules;

public record MachineKey(String tenantId, String entityType) {
    public static MachineKey base(String entityType) {
        return new MachineKey(null, entityType);
    }
}
