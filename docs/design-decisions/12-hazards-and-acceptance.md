# Decision 12: Hazards and Acceptance

## Status: DECIDED

| Hazard | Mechanism | Test (lifecycle-core) |
|---|---|---|
| H1 identity comparison | `StateName` value type; `equals` only | `H1StateNameByValueTest` |
| H2 partial guard evaluation | fold with no early return | `H2WhenMatcherTotalTest` (permutations) |
| H3 dry-run leaks | `evaluate`/`available` never reach commit/publish; ArchUnit forbids `core.rules` and resolver → `api.spi` | `H3EvaluatePurityTest`, `H3ArchitectureTest` |
| H4 absent optionals crash | every optional normalised to empty on construction; payload access via `Optional` | `H4OptionalFieldsTest` |
| H5 business constants in engine | only `lifecycle.` prefix and keywords `*`, `self` | `H5VocabularyTest` scans core+api sources for uppercase literals |
| H6 unbounded cascade | `hop` on envelope + `CYCLE` on inline revisit | `H6CascadeTerminationTest` (inline and transport) |
| H7 lost updates | optimistic version in commit; `Conflicted` outcome + detached audit | `H7ConcurrencyTest` (8 threads, one entity) |
| H8 mutable rules | records over `copyOf`; `AtomicReference` snapshot swap | `H8ImmutabilityTest` |
| H9 silent drop | sealed `Outcome`; every refusal committed to audit | `H9RefusalAuditTest` |
| H10 leaky effects | `$`-projections only | `H10SelfContainedEventTest` (consumed by a second, fresh engine) |

| Acceptance (brief §9) | Test |
|---|---|
| New entity type without engine change | `NewEntityTypeTest` |
| Same rules over in-memory and broker | `CrossEntityFlowTest` (in-memory now; Kafka parameter in phase 4) |
| No business names in engine source | `H5VocabularyTest` |
| Rule change is a reviewed data change | overlay fixture `SampleRules.acmeOverlay()` + `TenantOverlayTest` |
| "What can this actor do?" | `AvailableTest` |
| "Why refused?" months later | `H9RefusalAuditTest` (reason, state, ruleSetVersion on the row) |
| Same event twice | `ReplayTest` |
| Cycle terminates both paths | `H6CascadeTerminationTest` |
| Concurrent events detected | `H7ConcurrencyTest` |
| Machine renders for a human | `MermaidRendererTest` |
