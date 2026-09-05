# Decision 11: Storage and Transport SPIs

## Status: DECIDED

```java
public interface StateStore {
    Optional<StateRecord> find(EntityRef ref);
    CommitResult commit(Commit commit);                       // atomic, see DD-07
    void appendDetached(AuditRecord record);                  // conflicts; not tied to a version
    default OptionalLong countInState(String tenantId, String entityType, String state) { return OptionalLong.empty(); }
    default Optional<Outbox> outbox() { return Optional.empty(); }
}
public record Commit(EntityRef ref, long expectedVersion, String nextState /*null = no change*/,
                     String eventId, AuditRecord audit, List<LifecycleEvent> outbox) {}
public sealed interface CommitResult permits Committed, VersionMismatch, AlreadyApplied {}

public interface AuditQuery {                                 // read side, may be eventually consistent
    List<AuditRecord> byEntity(EntityRef ref);
    Optional<AuditRecord> byEventId(String eventId);
    List<AuditRecord> byCorrelation(String correlationId);
}
public interface Outbox { List<LifecycleEvent> unsent(int limit); void markSent(Collection<String> eventIds); }

public interface Transport {
    void publish(LifecycleEvent event);                       // kind is on the event
    void subscribe(Consumer<LifecycleEvent> inbound);         // signals only; throwing = nack/redeliver
    boolean supportsDelay();                                  // checked at startup when any rule has `after`
}

public interface LifecycleEngine {
    Outcome handle(LifecycleEvent event);                     // steps 1-4
    Decision evaluate(LifecycleEvent event);                  // steps 1-2, R9
    List<TransitionView> available(EntityRef ref, Actor actor);
}
```
The engine's whole knowledge of the outside world is these interfaces. No client library crosses into core.

### JDBC (phase 3)
Tables `lifecycle_state` (pk tenant/type/id, `version`), `lifecycle_inbox` (unique tenant/type/id/event_id,
`expires_at`), `lifecycle_audit` (append-only, indexed by entity and correlation), `lifecycle_outbox`
(`sent_at` nullable). Commit = one transaction: insert inbox (unique violation → AlreadyApplied),
`update ... where version = ?` (row count 0 → VersionMismatch), insert audit, insert outbox. Plain JDBC on a
`DataSource`.

### Mongo (phase 3)
Same four collections. Replica set → multi-document transaction. Standalone → inbox tail and pending outbox
embedded in the state document so the conditional single-document update stays atomic; audit written second
keyed by the state version so a crash between the two is repaired on the next commit. Health reports the mode.

Under contention MongoDB reports a lost race inside a transaction as a `TransientTransactionError` (write
conflict). The store retries the whole transaction at most three times with a short jittered backoff and then
returns `VersionMismatch`, because a write conflict on the state document *is* a version conflict in DD-07's
terms; the dispatcher re-reads and retries. Retrying forever was measured to livelock eight threads on one
entity. A duplicate inbox key inside a transaction may also surface as a write conflict, so the inbox is read
before it is inserted.
