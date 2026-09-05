package com.github.ifrugal.lifecycle.core.inmemory;

import com.github.ifrugal.lifecycle.api.model.AuditRecord;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.StateRecord;
import com.github.ifrugal.lifecycle.api.spi.AuditQuery;
import com.github.ifrugal.lifecycle.api.spi.Commit;
import com.github.ifrugal.lifecycle.api.spi.CommitResult;
import com.github.ifrugal.lifecycle.api.spi.Outbox;
import com.github.ifrugal.lifecycle.api.spi.StateStore;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Reference implementation of the storage seam (DD-07, DD-11). Atomicity is a monitor; the contract every other
 * store must honour is exactly what {@link #commit} does, in this order: inbox, version, audit, state, outbox.
 */
public final class InMemoryStateStore implements StateStore, AuditQuery, Outbox {

    private final Map<String, StateRecord> states = new HashMap<>();
    private final Map<String, String> inbox = new HashMap<>();
    private final List<AuditRecord> audit = new ArrayList<>();
    private final LinkedHashMap<String, LifecycleEvent> pendingOutbox = new LinkedHashMap<>();
    private final Clock clock;

    public InMemoryStateStore() {
        this(Clock.systemUTC());
    }

    public InMemoryStateStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized Optional<StateRecord> find(EntityRef ref) {
        return Optional.ofNullable(states.get(ref.key()));
    }

    @Override
    public synchronized CommitResult commit(Commit c) {
        String inboxKey = c.ref().key() + "|" + c.eventId();
        String first = inbox.get(inboxKey);
        if (first != null) {
            return new CommitResult.AlreadyApplied(first);
        }
        StateRecord current = states.get(c.ref().key());
        long currentVersion = current == null ? 0L : current.version();
        if (c.expectedVersion() != currentVersion) {
            return new CommitResult.VersionMismatch(c.expectedVersion(), currentVersion);
        }
        audit.add(c.audit());
        inbox.put(inboxKey, c.audit().auditId());
        long version = currentVersion;
        if (c.advances()) {
            version = currentVersion + 1;
            states.put(c.ref().key(), new StateRecord(c.ref(), c.nextState(), version, clock.instant(), c.eventId(), c.audit().ruleSetVersion()));
        }
        for (LifecycleEvent e : c.outbox()) {
            pendingOutbox.put(e.eventId(), e);
        }
        return new CommitResult.Committed(version, c.audit().auditId());
    }

    @Override
    public synchronized void appendDetached(AuditRecord record) {
        audit.add(record);
    }

    /** {@code tenantId == null} counts across all tenants, matching how base machines apply to every tenant without an overlay. */
    @Override
    public synchronized OptionalLong countInState(String tenantId, String entityType, String state) {
        long n = states.values().stream()
                .filter(r -> r.ref().type().equals(entityType))
                .filter(r -> tenantId == null || tenantId.equals(r.ref().tenantId()))
                .filter(r -> r.state().equals(state))
                .count();
        return OptionalLong.of(n);
    }

    @Override
    public Optional<Outbox> outbox() {
        return Optional.of(this);
    }

    @Override
    public synchronized List<LifecycleEvent> unsent(int limit) {
        return pendingOutbox.values().stream().limit(limit).toList();
    }

    @Override
    public synchronized void markSent(Collection<String> eventIds) {
        eventIds.forEach(pendingOutbox::remove);
    }

    @Override
    public synchronized List<AuditRecord> byEntity(EntityRef ref) {
        return audit.stream().filter(a -> a.entity().equals(ref)).toList();
    }

    @Override
    public synchronized Optional<AuditRecord> byEventId(String eventId) {
        return audit.stream().filter(a -> a.eventId().equals(eventId)).findFirst();
    }

    @Override
    public synchronized List<AuditRecord> byCorrelation(String correlationId) {
        return audit.stream().filter(a -> a.causation() != null && correlationId.equals(a.causation().correlationId())).toList();
    }

    /** Every audit row in commit order. */
    public synchronized List<AuditRecord> allAudit() {
        return List.copyOf(audit);
    }

    /** Every state record, for assertions. */
    public synchronized List<StateRecord> allStates() {
        return List.copyOf(states.values());
    }
}
