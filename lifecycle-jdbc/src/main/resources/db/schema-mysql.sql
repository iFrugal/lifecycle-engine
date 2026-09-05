-- lifecycle-engine JDBC schema, MySQL 8+ dialect. Same tables as PostgreSQL; differences: no partial indexes
-- (the single-ACTIVE rule is enforced with a generated column), datetime(6) for timestamps, JSON columns.

create table if not exists lifecycle_state (
  tenant_id     varchar(64)  not null default '',
  entity_type   varchar(64)  not null,
  entity_id     varchar(128) not null,
  state         varchar(128) not null,
  version       bigint       not null,
  updated_at    datetime(6)  not null,
  last_event_id varchar(64)  not null,
  rule_set_version varchar(128),
  primary key (tenant_id, entity_type, entity_id),
  index lifecycle_state_by_state (entity_type, state, tenant_id)
);

create table if not exists lifecycle_inbox (
  tenant_id     varchar(64)  not null default '',
  entity_type   varchar(64)  not null,
  entity_id     varchar(128) not null,
  event_id      varchar(64)  not null,
  audit_id      varchar(64)  not null,
  expires_at    datetime(6)  not null,
  primary key (tenant_id, entity_type, entity_id, event_id),
  index lifecycle_inbox_expiry (expires_at)
);

create table if not exists lifecycle_audit (
  audit_id       varchar(64)  primary key,
  event_id       varchar(64)  not null,
  tenant_id      varchar(64),
  entity_type    varchar(64)  not null,
  entity_id      varchar(128) not null,
  action         varchar(128) not null,
  actor_id       varchar(128) not null,
  actor_roles    json         not null,
  actor_kind     varchar(16)  not null,
  from_state     varchar(128),
  to_state       varchar(128),
  transition_id  varchar(128),
  outcome        varchar(16)  not null,
  reason         varchar(32),
  detail         text,
  rule_set_version varchar(128),
  at             datetime(6)  not null,
  correlation_id varchar(64)  not null,
  causation_id   varchar(64),
  hop            int          not null,
  index lifecycle_audit_by_entity (entity_type, entity_id, at),
  index lifecycle_audit_by_event (event_id),
  index lifecycle_audit_by_correlation (correlation_id, hop)
);

create table if not exists lifecycle_outbox (
  event_id     varchar(64)  primary key,
  body         json         not null,
  created_at   datetime(6)  not null,
  sent_at      datetime(6),
  index lifecycle_outbox_unsent (sent_at, created_at)
);

create table if not exists lifecycle_rule_set (
  id            bigint auto_increment primary key,
  tenant_id     varchar(64),
  entity_type   varchar(64)  not null,
  version       int          not null,
  status        varchar(16)  not null,
  format        varchar(8)   not null,
  body          longtext     not null,
  created_by    varchar(128) not null,
  created_at    datetime(6)  not null,
  activated_at  datetime(6),
  -- non-null only for ACTIVE rows, so the unique index below allows any number of DRAFT/RETIRED rows
  active_key    varchar(130) generated always as (case when status = 'ACTIVE' then concat(coalesce(tenant_id, ''), '|', entity_type) end) stored,
  unique key lifecycle_rule_set_version (tenant_id, entity_type, version),
  unique key lifecycle_rule_set_active (active_key)
);

create table if not exists lifecycle_task (
  task_id        varchar(64)  primary key,
  tenant_id      varchar(64),
  name           varchar(128) not null,
  assign_to      json         not null,
  created_by_type varchar(64) not null,
  created_by_id  varchar(128) not null,
  created_by_event_id varchar(64) not null,
  on_complete_action varchar(128) not null,
  target_type    varchar(64)  not null,
  target_id      varchar(128) not null,
  payload        json         not null,
  status         varchar(16)  not null,
  claimed_by     varchar(128),
  created_at     datetime(6)  not null,
  updated_at     datetime(6)  not null,
  correlation_id varchar(64)  not null,
  causation_id   varchar(64),
  hop            int          not null,
  index lifecycle_task_open (status, tenant_id, created_at),
  index lifecycle_task_by_entity (created_by_type, created_by_id)
);
