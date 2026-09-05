package com.github.ifrugal.lifecycle.jdbc;

/**
 * Every statement this module issues, in one place, so the SQL surface can be reviewed without reading Java.
 * All of it is portable across PostgreSQL, MySQL 8+ and H2: no dialect-specific syntax, no vendor functions
 * beyond {@code coalesce}, and {@code limit ?} which all three accept.
 */
public final class Sql {

    private Sql() {}

    // ---------------------------------------------------------------- state

    public static final String SELECT_STATE = """
            select tenant_id, entity_type, entity_id, state, version, updated_at, last_event_id, rule_set_version
              from lifecycle_state
             where tenant_id = ? and entity_type = ? and entity_id = ?""";

    public static final String SELECT_STATE_VERSION = """
            select version from lifecycle_state where tenant_id = ? and entity_type = ? and entity_id = ?""";

    /** The very first commit for an entity: version 0 means "no row yet", so the first stored version is 1. */
    public static final String INSERT_STATE = """
            insert into lifecycle_state (tenant_id, entity_type, entity_id, state, version, updated_at, last_event_id, rule_set_version)
            values (?, ?, ?, ?, 1, ?, ?, ?)""";

    /** The optimistic write (DD-07): zero rows updated means someone else moved the entity first. */
    public static final String UPDATE_STATE = """
            update lifecycle_state
               set state = ?, version = version + 1, updated_at = ?, last_event_id = ?, rule_set_version = ?
             where tenant_id = ? and entity_type = ? and entity_id = ? and version = ?""";

    public static final String COUNT_IN_STATE_ANY_TENANT = """
            select count(*) from lifecycle_state where entity_type = ? and state = ?""";

    public static final String COUNT_IN_STATE_FOR_TENANT = """
            select count(*) from lifecycle_state where entity_type = ? and state = ? and tenant_id = ?""";

    // ---------------------------------------------------------------- inbox

    public static final String INSERT_INBOX = """
            insert into lifecycle_inbox (tenant_id, entity_type, entity_id, event_id, audit_id, expires_at)
            values (?, ?, ?, ?, ?, ?)""";

    public static final String SELECT_INBOX_AUDIT_ID = """
            select audit_id from lifecycle_inbox
             where tenant_id = ? and entity_type = ? and entity_id = ? and event_id = ?""";

    public static final String DELETE_EXPIRED_INBOX = """
            delete from lifecycle_inbox where expires_at <= ?""";

    // ---------------------------------------------------------------- audit

    private static final String AUDIT_COLUMNS = """
            audit_id, event_id, tenant_id, entity_type, entity_id, action, actor_id, actor_roles, actor_kind,
            from_state, to_state, transition_id, outcome, reason, detail, rule_set_version, at,
            correlation_id, causation_id, hop""";

    public static final String INSERT_AUDIT = "insert into lifecycle_audit (" + AUDIT_COLUMNS + ")\n"
            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    public static final String SELECT_AUDIT_BY_ENTITY = "select " + AUDIT_COLUMNS + """
            
              from lifecycle_audit
             where entity_type = ? and entity_id = ? and tenant_id = ?
             order by at, audit_id""";

    public static final String SELECT_AUDIT_BY_ENTITY_NO_TENANT = "select " + AUDIT_COLUMNS + """
            
              from lifecycle_audit
             where entity_type = ? and entity_id = ? and tenant_id is null
             order by at, audit_id""";

    public static final String SELECT_AUDIT_BY_EVENT = "select " + AUDIT_COLUMNS + """
            
              from lifecycle_audit where event_id = ? order by at, audit_id""";

    public static final String SELECT_AUDIT_BY_CORRELATION = "select " + AUDIT_COLUMNS + """
            
              from lifecycle_audit where correlation_id = ? order by hop, at, audit_id""";

    // ---------------------------------------------------------------- outbox

    public static final String INSERT_OUTBOX = """
            insert into lifecycle_outbox (event_id, body, created_at, sent_at) values (?, ?, ?, null)""";

    public static final String SELECT_UNSENT_OUTBOX = """
            select event_id, body from lifecycle_outbox
             where sent_at is null
             order by created_at, event_id
             limit ?""";

    public static final String MARK_OUTBOX_SENT = """
            update lifecycle_outbox set sent_at = ? where event_id = ? and sent_at is null""";

    // ---------------------------------------------------------------- rule sets

    public static final String SELECT_ACTIVE_RULE_SETS = """
            select tenant_id, entity_type, version, format, body
              from lifecycle_rule_set
             where status = 'ACTIVE'
             order by entity_type, version""";

    public static final String INSERT_RULE_SET_DRAFT = """
            insert into lifecycle_rule_set (tenant_id, entity_type, version, status, format, body, created_by, created_at)
            values (?, ?, ?, 'DRAFT', ?, ?, ?, ?)""";

    public static final String SELECT_RULE_SET_KEY = """
            select tenant_id, entity_type from lifecycle_rule_set where id = ?""";

    public static final String RETIRE_ACTIVE_RULE_SET = """
            update lifecycle_rule_set set status = 'RETIRED'
             where status = 'ACTIVE' and entity_type = ? and coalesce(tenant_id, '') = ?""";

    public static final String ACTIVATE_RULE_SET = """
            update lifecycle_rule_set set status = 'ACTIVE', activated_at = ? where id = ?""";

    public static final String RETIRE_RULE_SET = """
            update lifecycle_rule_set set status = 'RETIRED' where id = ?""";

    // ---------------------------------------------------------------- tasks

    private static final String TASK_COLUMNS = """
            task_id, tenant_id, name, assign_to, created_by_type, created_by_id, created_by_event_id,
            on_complete_action, target_type, target_id, payload, status, claimed_by, created_at, updated_at,
            correlation_id, causation_id, hop""";

    public static final String INSERT_TASK = "insert into lifecycle_task (" + TASK_COLUMNS + ")\n"
            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    public static final String SELECT_TASK = "select " + TASK_COLUMNS + """
            
              from lifecycle_task where task_id = ?""";

    /** Compare-and-set on status (DD-10): two claimants cannot both win because only one sees the old status. */
    public static final String UPDATE_TASK_CAS = """
            update lifecycle_task
               set name = ?, assign_to = ?, on_complete_action = ?, target_type = ?, target_id = ?, payload = ?,
                   status = ?, claimed_by = ?, updated_at = ?
             where task_id = ? and status = ?""";

    public static final String SELECT_OPEN_TASKS = "select " + TASK_COLUMNS + """
            
              from lifecycle_task where status in ('OPEN', 'CLAIMED') order by created_at, task_id""";

    public static final String SELECT_OPEN_TASKS_FOR_TENANT = "select " + TASK_COLUMNS + """
            
              from lifecycle_task where status in ('OPEN', 'CLAIMED') and tenant_id = ? order by created_at, task_id""";

    public static final String SELECT_TASKS_BY_ENTITY = "select " + TASK_COLUMNS + """
            
              from lifecycle_task where created_by_type = ? and created_by_id = ? order by created_at, task_id""";
}
