package com.github.ifrugal.lifecycle.api.rules;

/** One thing wrong with a rule set. {@code transitionId} is null for document-level problems. */
public record Problem(String tenantId, String entityType, String transitionId, String message) {

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (tenantId != null) {
            sb.append('[').append(tenantId).append("] ");
        }
        sb.append(entityType);
        if (transitionId != null) {
            sb.append(" / ").append(transitionId);
        }
        return sb.append(": ").append(message).toString();
    }
}
