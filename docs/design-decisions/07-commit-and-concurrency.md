# Decision 07: Commit, Dedupe and Concurrency (D4, D6, D7)

## Status: DECIDED

## Context
D4 (at-least-once), D6 (transaction boundary) and D7 (concurrency) are one question: what is the unit of
atomicity? H3 requires a dry run with no consequences; H7 requires conflicts to be detectable.

## Decision: read → decide (pure) → commit (atomic) → publish
```
1 read      store.find(entity) or StateRecord.initial(entity, machine.initial) at version 0
2 decide    TransitionResolver.decide(machine, record, event) → Decision.Match | Decision.Refuse   ← pure
3 commit    store.commit(Commit(ref, expectedVersion, nextState?, eventId, audit, outbox))         ← one atomic call
4 publish   transport.publish(each outbox event); outbox.markSent(ids)                             ← after commit only
```
`evaluate` and `available` run steps 1-2 only. A dry run is not a flag that skips a write; it is the absence
of steps 3-4 from the call path, enforced by ArchUnit (the resolver may not depend on `api.spi`).

### The commit contract
The store must perform, all-or-nothing:
1. **Inbox**: `(entity, eventId)` unique. Already present → `AlreadyApplied(firstAuditId)`. Checked first, so a
   replay is recognised regardless of version.
2. **Version**: `expectedVersion == current.version`, else `VersionMismatch(expected, actual)`.
3. **Audit** append (also for refusals: `nextState == null` means no state change and no version bump).
4. **State**: if `nextState != null`, set state and `version + 1`.
5. **Outbox** append of emitted events.

### Dedupe (D4)
Explicit, by event id, inside the commit. Replays return `Duplicate` and are audited as such; the first
attempt's outputs are not re-emitted. Natural idempotence remains as backstop when the inbox has expired
(default retention 7 days): an old replay usually finds `NO_MATCH`, visibly. Refusals also enter the inbox,
so ten redeliveries of a refused event give one REFUSED and nine DUPLICATE rows.

### Concurrency (D7, R12)
Optimistic version on the state record. `VersionMismatch` → engine returns `Conflicted` and writes a
**detached** audit row (`StateStore.appendDetached`) so contention is visible alongside everything else.
`Dispatcher` (fronting transport delivery) re-runs from step 1 up to 3 times, then throws
`RedeliveryRequested` so the transport redelivers with its own backoff. A caller-supplied `expectedVersion`
is never retried: the user should see the screen was stale.

Contended test assertion: final version == number of Applied outcomes; APPLIED audit rows unique per event;
conflict count > 0.

### Transaction boundary (D6)
The store owns it. The engine is transaction-agnostic. Publishing happens after commit; a crash between
commit and publish is repaired by the backend module's `OutboxRelay`, which republishes unsent rows
(at-least-once, deduped downstream by event id). Effects are never executed by the engine, only emitted.

## Rejected
- Transport-guaranteed exactly-once: no transport worth using guarantees it end to end.
- Pessimistic locks: incompatible with a transport-agnostic core and with re-entrant traffic.
- Serialising per entity outside the engine: fine as a transport optimisation, insufficient as the only guard.
