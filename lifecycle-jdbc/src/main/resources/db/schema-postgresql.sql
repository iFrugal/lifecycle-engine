-- lifecycle-engine JDBC schema, PostgreSQL dialect (DD-05, DD-07, DD-11).
-- One transaction per commit: inbox insert (unique violation = AlreadyApplied), state update where version = ?
-- (0 rows = VersionMismatch), audit insert, outbox insert.

create table if not exists lifecycle_state (
  tenant_id     varchar(64)  not null default '',   -- '' = no tenant; part of the key so nulls never collide
  entity_type   varchar(64)  not null,
  entity_id     varchar(128) not null,
  state         varchar(128) not null,
  version       bigint       not null,
  updated_at    timestamptz  not null,
  last_event_id varchar(64)  not null,
  rule_set_version varchar(128),
  primary key (tenant_id, entity_type, entity_id)
);
create index if not exists lifecycle_state_by_state on lifecycle_state (entity_type, state, tenant_id);

create table if not exists lifecycle_inbox (
  tenant_id     varchar(64)  not null default '',
  entity_type   varchar(64)  not null,
  entity_id     varchar(128) not null,
  event_id      varchar(64)  not null,
  audit_id      varchar(64)  not null,
  expires_at    timestamptz  not null,
  primary key (tenant_id, entity_type, entity_id, event_id)
);
create index if not exists lifecycle_inbox_expiry on lifecycle_inbox (expires_at);

create table if not exists lifecycle_audit (
  audit_id       varchar(64)  primary key,
  event_id       varchar(64)  not null,
  tenant_id      varchar(64),
  entity_type    varchar(64)  not null,
  entity_id      varchar(128) not null,
  action         varchar(128) not null,
  actor_id       varchar(128) not null,
  actor_roles    text         not null,            -- JSON array
  actor_kind     varchar(16)  not null,
  from_state     varchar(128),
  to_state       varchar(128),
  transition_id  varchar(128),
  outcome        varchar(16)  not null,            -- APPLIED | REFUSED | CONFLICTED | DUPLICATE
  reason         varchar(32),
  detail         text,
  rule_set_version varchar(128),
  at             timestamptz  not null,
  correlation_id varchar(64)  not null,
  causation_id   varchar(64),
  hop            integer      not null
);
create index if not exists lifecycle_audit_by_entity on lifecycle_audit (entity_type, entity_id, at);
create index if not exists lifecycle_audit_by_event on lifecycle_audit (event_id);
create index if not exists lifecycle_audit_by_correlation on lifecycle_audit (correlation_id, hop);

create table if not exists lifecycle_outbox (
  event_id     varchar(64)  primary key,
  body         text         not null,              -- the full LifecycleEvent as JSON
  created_at   timestamptz  not null,
  sent_at      timestamptz
);
create index if not exists lifecycle_outbox_unsent on lifecycle_outbox (created_at) where sent_at is null;

create table if not exists lifecycle_rule_set (
  id            bigserial primary key,
  tenant_id     varchar(64),                       -- null = the base rule set
  entity_type   varchar(64)  not null,
  version       integer      not null,
  status        varchar(16)  not null,             -- DRAFT | ACTIVE | RETIRED
  format        varchar(8)   not null,             -- yaml | json
  body          text         not null,
  created_by    varchar(128) not null,
  created_at    timestamptz  not null,
  activated_at  timestamptz,
  unique (tenant_id, entity_type, version)
);
-- at most one ACTIVE per (tenant, type)
create unique index if not exists lifecycle_rule_set_active
  on lifecycle_rule_set (coalesce(tenant_id, ''), entity_type) where status = 'ACTIVE';

create table if not exists lifecycle_task (
  task_id        varchar(64)  primary key,
  tenant_id      varchar(64),
  name           varchar(128) not null,
  assign_to      text         not null,            -- JSON array of roles
  created_by_type varchar(64) not null,
  created_by_id  varchar(128) not null,
  created_by_event_id varchar(64) not null,
  on_complete_action varchar(128) not null,
  target_type    varchar(64)  not null,
  target_id      varchar(128) not null,
  payload        text         not null,            -- JSON object
  status         varchar(16)  not null,            -- OPEN | CLAIMED | COMPLETED | CANCELLED
  claimed_by     varchar(128),
  created_at     timestamptz  not null,
  updated_at     timestamptz  not null,
  correlation_id varchar(64)  not null,
  causation_id   varchar(64),
  hop            integer      not null
);
create index if not exists lifecycle_task_open on lifecycle_task (status, tenant_id, created_at);
create index if not exists lifecycle_task_by_entity on lifecycle_task (created_by_type, created_by_id);
