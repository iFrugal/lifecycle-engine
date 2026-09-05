# Decision 13: Spring Boot Starter

## Status: DECIDED

## Context
R11 keeps Spring out of the core. Applications still want one dependency and a YAML block. The starter is the
only module that knows about all the others; it wires by configuration and never by classpath guessing alone.

## Decision
Module `lifecycle-spring-boot-starter`, package `com.github.ifrugal.lifecycle.starter`, Spring Boot 4.x
(`spring-boot.version` in the root pom). Auto-configuration registered in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Every adapter module is an
`optional` dependency of the starter; each configuration class is `@ConditionalOnClass` on the adapter's entry
class and `@ConditionalOnProperty` on the selector below. Every bean is `@ConditionalOnMissingBean` so an
application can replace any piece.

### Properties (`lifecycle.*`)
```yaml
lifecycle:
  enabled: true
  rules:
    files: [classpath:rules/, file:/etc/app/rules/]   # FileDefinitionSource / ClasspathDefinitionSource (explicit resource list for classpath)
    jdbc: false                                       # add JdbcRuleSetSource to the composite (needs store jdbc or a DataSource)
    mongo: false                                      # add MongoRuleSetSource to the composite
    reload:
      poll: 30s                                       # 0 = no polling; reload only via endpoint/bean call
      fail-fast: true                                 # refuse to start when the first load has problems
  store: memory | jdbc | mongo                        # default memory
  jdbc:
    dialect: postgresql | mysql | h2                  # default postgresql
    install-schema: false                             # run SchemaInstaller at startup (dev only)
    inbox-retention: 7d
    outbox-relay:
      enabled: true
      period: 5s
      batch-size: 100
  mongo:
    database: lifecycle                               # uses the app's MongoClient bean
    transactions: true
    inbox-retention: 7d
    outbox-relay: { enabled: true, period: 5s, batch-size: 100 }
  transport: memory | kafka                           # default memory
  kafka:
    bootstrap-servers: ...
    signals-topic: lifecycle.signals
    notifications-topic: lifecycle.notifications
    dead-letter-topic: lifecycle.signals.dlq
    consumer-group: lifecycle-engine
    max-delivery-attempts: 5
    retry-backoff: 200ms
  dispatcher:
    conflict-retries: 3
  tasks:
    enabled: false                                    # TaskService + TaskStore matching `store`; notification consumer wired
  engine:
    actor-id: lifecycle-engine
  management:
    reload-endpoint: true                             # actuator endpoint `lifecyclerules` (GET status, POST reload)
    health: true                                      # HealthIndicator: snapshot loaded, version, machine count, last reload problems
    metrics: true                                     # Micrometer: lifecycle.outcomes{outcome,reason}, lifecycle.reloads{applied}, lifecycle.outbox.pending
```

### Beans (in wiring order)
1. `GuardRegistry` from every `GuardPredicate` bean in the context (bean-based discovery, the house convention).
2. `DefinitionSource`: composite of the configured sources.
3. `StateStore` (+ `AuditQuery` exposed as the same bean when the store implements it).
4. `DefinitionRegistry(source, guards, store)`; `reloadOrThrow()` on `ApplicationReadyEvent` when fail-fast,
   `reload()` otherwise; a `@Scheduled`-free poller thread when `poll > 0` that reloads only if `fingerprint()` changed.
5. `Transport`.
6. `LifecycleEngine` = `DefaultLifecycleEngine(registry, store, transport, guards, EngineConfig)`.
7. `Dispatcher(engine, conflictRetries).attachTo(transport)`.
8. Outbox relay scheduled on a single daemon thread when the store exposes an outbox.
9. Tasks: `TaskStore` for the selected store, `TaskService`, and for `transport: memory` an `onNotification`
   hook, for `kafka` a `KafkaNotificationConsumer` filtering `lifecycle.task.create`.
10. Actuator endpoint, health indicator, Micrometer binder (each `@ConditionalOnClass` of the actuator/micrometer types).

### Testing
`@SpringBootTest` slices per selector with the in-memory store and transport (no containers) proving: guards
discovered, rules loaded from classpath, an event handled end to end, reload endpoint works, health reports the
snapshot version. One `@Testcontainers` test with `store: jdbc` (Postgres) and one with `transport: kafka`.

## Rejected
- Java SPI (`META-INF/services`) discovery of guards: notification-service DD-06 already rejected SPI for beans.
- Auto-selecting the store from the classpath: two adapters present would pick silently; the selector is explicit.
