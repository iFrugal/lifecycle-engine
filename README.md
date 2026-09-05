# lifecycle-engine

A declarative lifecycle (state machine) engine. Rules live in reviewable data, outputs are events, and the
engine does not know or care how those events reach it.

The engine is a function of `(current state, event, definition) -> (next state, outputs)`. Everything else,
where events come from and where outputs go, is an adapter.

## Modules

| Module | Purpose |
|---|---|
| `lifecycle-api` | Records, sealed outcomes, SPIs (`StateStore`, `Transport`, `DefinitionSource`, `GuardPredicate`), rule document model. No dependencies. |
| `lifecycle-core` | Rule compiler and validators, pure `TransitionResolver`, `DefaultLifecycleEngine`, `DefinitionRegistry` with tenant overlays, in-memory store and transport, Mermaid renderer. Depends on api and slf4j only. |
| `lifecycle-rules-yaml` | YAML/JSON parser and file source. |
| `lifecycle-json` | Jackson codec for the envelope and audit records, shared by transports and stores. |
| `lifecycle-jdbc`, `lifecycle-mongo` | (phase 3) stored rule source, atomic commit, outbox relay. |
| `lifecycle-transport-kafka`, `lifecycle-tasks` | (phase 4) |
| `lifecycle-spring-boot-starter` | (phase 5) |

## Design

The design decisions live in [docs/design-decisions](docs/design-decisions/00-decision-log.md). Read the log first.

## Quick start (in memory)

```java
var source   = new InMemoryDefinitionSource(List.of(orderRules, shipmentRules));
var guards   = GuardRegistry.of(new RefundWindowOpen());
var store    = new InMemoryStateStore();
var registry = new DefinitionRegistry(source, guards, store);
registry.reload();                                   // validates, swaps an immutable snapshot

try (var transport = new InMemoryTransport()) {
    var engine = new DefaultLifecycleEngine(registry, store, transport, guards);
    new Dispatcher(engine, 3).attachTo(transport);   // emitted signals loop back through here

    Outcome outcome = engine.handle(LifecycleEvent.builder()
        .entity(new EntityRef(null, "order", "o-1"))
        .action("PAY")
        .actor(Actor.of("u-42", "customer"))
        .payload(Map.of("payment", Map.of("status", "AUTHORISED", "amount", 120)))
        .build());

    switch (outcome) {
        case Outcome.Applied a    -> ...
        case Outcome.Refused r    -> ...   // r.reason(), r.detail(); also in the audit
        case Outcome.Conflicted c -> ...
        case Outcome.Duplicate d  -> ...
    }
}
```
