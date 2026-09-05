# Decision 02: Event Envelope (D2)

## Status: DECIDED

## Context
Outputs are inputs (R2). The envelope is therefore the contract for the whole system, must carry identity for
dedupe (R10), a causation chain and hop count so cascades terminate across a broker (H6), and everything its
consumer needs because the consumer may be in another process a week later (H10).

## Decision
One symmetric record, `LifecycleEvent`:

| Field | Type | Meaning |
|---|---|---|
| `eventId` | String | Dedupe key. Caller-assigned or generated. Emitted events get a **deterministic** id derived from `(parent eventId, index)` so a replayed parent re-emits identical children. |
| `kind` | `SIGNAL` / `NOTIFICATION` | Signal: addressed to the engine, may move an entity. Notification: for the outside world; the engine never consumes it. Set by the emitter, never inferred from the action name. |
| `entity` | `EntityRef(tenantId?, type, id)` | Whom it concerns. `tenantId` is optional and opaque. |
| `action` | String | Reused across states; never identifies an edge alone. |
| `actor` | `Actor(id, roles, kind)` | Roles **arrive on the event**; the engine does no lookups. Empty roles is a legal actor (H4). Kinds: HUMAN, SERVICE, ENGINE, SCHEDULER. |
| `payload` | `Map<String,Object>` | JSON-shaped, deeply immutable copy on construction. |
| `occurredAt` | Instant | |
| `deliverAt` | Instant? | Set only for timers (`after:`). Transport honours it or refuses at startup. |
| `causation` | `Causation(correlationId, causationId, hop)` | Engine sets `causationId` = consumed event id, copies `correlationId`, `hop + 1`. Inbound event without causation gets a fresh root at hop 0. |
| `expectedVersion` | Long? | Optional optimistic precondition from a UI that rendered controls against a version. Mismatch → `Conflicted`, not retried. |

Engine-raised events carry the engine actor (`lifecycle-engine`, role `lifecycle-engine`, kind ENGINE), so a
rule that consumes an engine-emitted signal lists that role like any other.

## Rejected
- Distinct command and event shapes: doubles the surface and breaks R2 (an output that cannot be fed back in).
- Inferring kind from the action name: reintroduces business vocabulary into the core (H5).
