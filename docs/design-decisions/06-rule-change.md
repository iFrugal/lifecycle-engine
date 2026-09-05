# Decision 06: Rules Change Under Live Entities (D10)

## Status: DECIDED

## Decision
- **No pinning.** Entities are evaluated against the current snapshot. Pinning creates a version matrix
  nobody can review and defeats the point of changing a rule.
- **Structural validation on every reload**; failure keeps the previous snapshot. The reload analogue of
  "refuse to start".
- **Orphan detection where the store can count.** When a reload removes a state present in the previous
  snapshot (or removes a machine), the registry calls `StateStore.countInState(tenant, type, state)`.
  Non-zero → reload fails naming the state and count. Empty (unsupported) → warning naming the state.
  In-memory, JDBC and Mongo stores all answer.
- **Every audit row carries the snapshot version** (`ruleSetVersion`) it was decided under, so "why was this
  refused in March" is answerable from audit + rule history (R8).
- **Renames are two changes, not a migration DSL.** Keep the old state, add
  `from: OLD, on: MIGRATE, roles: [system], to: NEW`, drain with a script that raises the event, then remove
  the old state in a later change. The orphan check enforces the order.
