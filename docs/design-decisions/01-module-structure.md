# Decision 01: Module Structure

## Status: DECIDED

## Context
The brief requires an entity-agnostic (R3), transport-agnostic (R4), storage-pluggable (R7) core with no
framework dependency (R11), testable with no container and no broker. The house convention (notification-service,
all-about-persistence) is `-api` + `-core` + one module per backend + starter.

## Decision
```
lifecycle-engine/
├── lifecycle-api                    records, sealed outcomes, SPIs, rule document model — zero dependencies
├── lifecycle-core                   compiler+validators, pure resolver, engine, dispatcher, registry (tenant overlays),
│                                    in-memory store + transport, abstract StoredRuleSetSource, Mermaid renderer — api + slf4j
├── lifecycle-rules-yaml             Jackson YAML/JSON parser, FileDefinitionSource                          (phase 2)
├── lifecycle-jdbc                   JdbcRuleSetSource, JdbcStateStore (one-transaction commit), JdbcOutboxRelay, DDL  (phase 3)
├── lifecycle-mongo                  same four roles over mongodb-driver-sync                                (phase 3)
├── lifecycle-transport-kafka        kind→topic mapping, entity as record key, consumer → Dispatcher, manual ack (phase 4)
├── lifecycle-tasks                  TaskService, TaskStore SPI, consumer of lifecycle.task.create           (phase 4)
└── lifecycle-spring-boot-starter    auto-config, bean-discovered guards, reload endpoint, health, metrics   (phase 5)
```
Dependency direction: api ← core ← {rules-yaml, jdbc, mongo, kafka, tasks} ← starter. jdbc and mongo also
depend on rules-yaml so a stored body is parsed by the same parser as a file.

Packages: `com.github.ifrugal.lifecycle.api.{model,spi,guard,rules}` and
`com.github.ifrugal.lifecycle.core.{rules,registry,engine,inmemory,render}`.

Baseline: Java 21 (parent default; only JDK on the build machines). Records for all api types; no Lombok in api/core.
Tests: JUnit 5 + AssertJ (+ ArchUnit in core for the H3 seam).

## Why not build the stores on all-about-persistence
The commit contract (DD-07) needs a conditional write and a multi-row transaction in a single call.
`persistence-api` exposes neither optimistic locking nor transactions. Wrapping it would put the atomicity
the design rests on behind an abstraction that cannot promise it. JDBC and Mongo modules use the drivers
directly. A read-side adapter over `persistence-api` can be added later without touching the core.

## Alternatives considered
- Module inside `all-about-persistence/app-building-blocks`: rejected, the engine is not a persistence concern
  and would inherit Spring Boot 3.2 and TestNG.
- Single module: rejected, callers would drag Jackson/JDBC/Kafka into processes that only need api + core.
