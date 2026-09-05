# lifecycle-engine - Design Decision Log

Answers the *Declarative Lifecycle Engine* brief. Brief decision ids (D1-D10), requirements (R1-R12) and
hazards (H1-H10) are referenced throughout.

## Status legend
- **DECIDED** - final, documented with reasoning
- **OPEN** - needs input
- **PARKED** - deferred

---

| # | Topic | Brief | Status | Decision | Document |
|---|-------|-------|--------|----------|----------|
| 01 | Module structure | R3, R4, R7, R11 | DECIDED | `-api` (zero deps) / `-core` (no framework) / one module per backend or transport / starter. Stores use drivers directly, not `persistence-api`. | [01-module-structure.md](./01-module-structure.md) |
| 02 | Event envelope | D2, R2, H6, H10 | DECIDED | One symmetric `LifecycleEvent` in and out. `eventId` for dedupe, `Causation(correlationId, causationId, hop)` for termination, `kind` SIGNAL/NOTIFICATION, optional `expectedVersion`. Roles arrive on the event. | [02-event-envelope.md](./02-event-envelope.md) |
| 03 | Guards | D1, R5, H2, H4 | DECIDED | `roles` any-of, `when` subset match on payload paths (literal / list / exists), `guard` = named code predicate registered at startup. Static precedence and ambiguity check at load. No expression language. | [03-guards.md](./03-guards.md) |
| 04 | Rule format | R1, R6 | DECIDED | One document per entity type; a transition reads from / on / roles / when / guard / to / emit / task. Stable transition `id`. Emitted payloads are `$`-projections, never expressions. Dotted state names are a matching hierarchy only. | [04-rule-format.md](./04-rule-format.md) |
| 05 | Rule sources and tenancy | D9 | DECIDED | `DefinitionSource` adapter. YAML shipped; abstract `StoredRuleSetSource` with JDBC and Mongo impls; composite. Base rule set per type plus optional tenant overlays merged by transition id (replace / disable / add). | [05-rule-sources-and-tenancy.md](./05-rule-sources-and-tenancy.md) |
| 06 | Rule change under live entities | D10 | DECIDED | No pinning. Structural validation on every reload, failure keeps the old snapshot. Orphan detection via `StateStore.countInState` where supported. Audit rows carry the snapshot version. Renames are edges, not a DSL. | [06-rule-change.md](./06-rule-change.md) |
| 07 | Commit, dedupe and concurrency | D4, D6, D7, R10, R12, H3, H7 | DECIDED | Pure `decide`, then one atomic `StateStore.commit` (version check + inbox + audit + outbox). Publish after commit; relay drains outbox. Optimistic version; `Conflicted` is its own outcome, retried by the dispatcher. | [07-commit-and-concurrency.md](./07-commit-and-concurrency.md) |
| 08 | Ordering | D5 | DECIDED | Engine requires nothing. Disorder yields an audited refusal or a conflict, never a wrong history. Transports configured for per-entity ordering where they can be. | [08-ordering.md](./08-ordering.md) |
| 09 | Cascades | D3, H6 | DECIDED | Same entity: inline by default. Other entity: transport by default. Per-rule `dispatch` override requires a `reason`. Hop count on the event; inline `CYCLE` detection on (entity, action). Each step is its own commit; no rollback of earlier steps. | [09-cascades.md](./09-cascades.md) |
| 10 | Tasks | D8 | DECIDED | Optional module over the event mechanism. `task:` compiles to a `lifecycle.task.create` notification. Module owns claimants, completion and the deferred cross-entity signal. | [10-tasks.md](./10-tasks.md) |
| 11 | Storage and transport SPIs | R4, R7 | DECIDED | `StateStore` (find / commit / appendDetached / countInState / outbox), `AuditQuery`, `Outbox`, `Transport` (publish / subscribe / supportsDelay). Engine surface: `handle`, `evaluate`, `available`. | [11-storage-and-transport-spi.md](./11-storage-and-transport-spi.md) |
| 12 | Hazards and acceptance | H1-H10, brief §9 | DECIDED | Mechanism and named test for every hazard and every acceptance check. | [12-hazards-and-acceptance.md](./12-hazards-and-acceptance.md) |
| 13 | Spring Boot starter | R11 | DECIDED | Optional adapter deps, explicit `lifecycle.store` / `lifecycle.transport` selectors, bean-discovered guards, poll-based reload, actuator endpoint + health + metrics. | [13-spring-boot-starter.md](./13-spring-boot-starter.md) |

## Open items carried from the review
- Real 73-transition reference rule set: not yet received. YAML schema stays *provisional* until it is.
- The ten effect names and what consumers need in the payload.
- Whether the Kafka adapter resolves actor roles inbound (current position: roles arrive on the message).

## Key principles
1. **Entity-agnostic**: no business state name or entity type in engine source. Enforced by test.
2. **Events are the only currency**: what goes in is the type that comes out.
3. **Pure decide, atomic commit**: side effects happen in exactly one store call and one publish step.
4. **Loud refusals**: sealed outcomes, every refusal audited with a reason. No null, no boolean.
5. **Small enough to hold in one head**: if a process variable, parallel gateway or form appears, adopt a platform instead.
