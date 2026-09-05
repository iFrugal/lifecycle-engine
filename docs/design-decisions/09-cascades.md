# Decision 09: Cascades (D3, H6)

## Status: DECIDED

## Decision
| Emitted | Default dispatch | Semantics |
|---|---|---|
| signal to the **same** entity | `inline` | Engine loops synchronously from step 1; each step its own commit, version and audit row; `hop + 1`. `handle` returns when the chain ends, so a caller sees the follow-on state in one call. |
| signal to **another** entity | `transport` | Eventually consistent between entities, stated not hidden. |
| notification | `transport` | Always. The engine never consumes or executes it. |
| timer (`after:`) | `transport` | A signal with `deliverAt`. Transport that cannot delay → refused at startup, with the rule id. |

A timer is never inline by default, and an explicit `dispatch: inline` on a timer is refused at load: only a transport can delay a message. (Found by the H6 and acceptance timer tests during phase 1; the compiler originally keyed the default on the target alone.)

A rule may override with `dispatch: inline | transport`. The compiler requires a `reason:` beside any
override so the diff carries the justification. Inline cross-entity couples two lifecycles into one failure
domain; the reason should say why that is acceptable.

### Termination
- **On the event**: `hop > maxHops` (rule set setting, default 32) → `Refused(HOP_LIMIT)` wherever the event
  is consumed, in process or across brokers.
- **In process**: an inline chain that revisits `(entity, action)` → `Refused(CYCLE)` before the hop limit,
  because in process we can see it.

### No rollback
A later inline step failing does not roll back earlier ones. Each was a real transition, reviewed as such;
the causation chain in the audit tells the whole story. A compensating edge is a rule like any other. This is
the price of not being a workflow platform.

### Emitted event ids are deterministic
`UUID.nameUUIDFromBytes(parentEventId + "#" + index)`, so a replayed parent re-emits identical children and
downstream dedupe holds.
