package com.github.ifrugal.lifecycle.mongo;

/** Collection names used by this module. Field names inside each document are camelCase, mirroring the JDBC
 * schema (lifecycle-jdbc's {@code db/schema-postgresql.sql}) so the two backends read alike. */
public final class MongoCollections {

    public static final String STATE = "lifecycleState";
    public static final String INBOX = "lifecycleInbox";
    public static final String AUDIT = "lifecycleAudit";
    public static final String OUTBOX = "lifecycleOutbox";
    public static final String RULE_SET = "lifecycleRuleSet";
    public static final String TASK = "lifecycleTask";

    private MongoCollections() {}
}
