# Decision 05: Rule Sources and Tenancy (D9)

## Status: DECIDED

## Context
The brief's most consequential question. Files versioned with code are honest but make every change a
deployment. A runtime store lets owners change rules but needs governance. The user requires both routes and
optional per-tenant overrides on a shared base.

## Decision
### Adapter
```java
public interface DefinitionSource {
    Collection<RuleSetDocument> load();   // parsed, not compiled
    String fingerprint();                 // changes iff load() would
}
```
- `FileDefinitionSource` (rules-yaml): directory or classpath, fingerprint = content hash.
- `StoredRuleSetSource` (core, abstract): `protected abstract List<StoredRuleSet> fetchActive()`; parses each
  body with the shared `RuleSetParser`. `JdbcRuleSetSource` and `MongoRuleSetSource` implement `fetchActive`.
  Anyone with another database extends the same class.
- `CompositeDefinitionSource`: concatenates sources. Typical deployment: bases from files, tenant overlays
  from a table.
- `InMemoryDefinitionSource` (core): for tests and embedding.

### Stored shape: documents, not rows
```sql
create table lifecycle_rule_set (
  id bigserial primary key,
  tenant_id varchar(64),                -- null = base
  entity_type varchar(64) not null,
  version integer not null,
  status varchar(16) not null,          -- DRAFT | ACTIVE | RETIRED
  format varchar(8) not null,           -- yaml | json
  body text not null,
  created_by varchar(128) not null, created_at timestamptz not null, activated_at timestamptz,
  unique (tenant_id, entity_type, version)
);
create unique index lifecycle_rule_set_active on lifecycle_rule_set (coalesce(tenant_id,''), entity_type) where status = 'ACTIVE';
```
The engine reads ACTIVE rows only. Drafting/approval/rollback is governance, not shipped; the compiler's
validator is public (`RuleCompiler.compile(...).problems()`) so a governance surface can validate before
activating. Rollback = activate the previous version.

### Tenant overlays
- Overlay = `RuleSetDocument` with `tenantId` set and the `entityType` of an existing base. No base → load fails.
- Merge by transition `id`: same id **replaces whole**; `disabled: true` removes; new id adds. Never deep-merge.
- `states` additive only (entities may sit in base states). `initial`, `maxHops` may be overridden.
- Validation runs on each merged machine.
- Resolution: `machine(tenantId, type)` falls back to `machine(null, type)`. Emitted signals keep the emitting
  event's tenant; a cascade never crosses tenants.

### Reload
All-or-nothing: load → group (exactly one base per type) → merge overlays → compile all → orphan check
(DD-06) → atomic swap of an immutable `Snapshot(version, Map<(tenant,type), Machine>)`. Any problem keeps
the previous snapshot live and returns the problems.

## Rejected
- Deep-merging overlay fields: unreviewable diffs, validation cannot see the effective rule.
- Normalised rule tables: 5+ tables, a reassembler duplicating the parser, and stored form drifting from file form.
