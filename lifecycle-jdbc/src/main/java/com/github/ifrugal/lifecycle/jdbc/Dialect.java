package com.github.ifrugal.lifecycle.jdbc;

/**
 * The three SQL flavours the module ships DDL for. Only the schema and a handful of error codes differ; every
 * statement in {@link Sql} is portable across all three.
 */
public enum Dialect {

    POSTGRESQL("db/schema-postgresql.sql"),
    MYSQL("db/schema-mysql.sql"),
    H2("db/schema-h2.sql");

    private final String schemaResource;

    Dialect(String schemaResource) {
        this.schemaResource = schemaResource;
    }

    /** Classpath path of this dialect's DDL, as accepted by {@link ClassLoader#getResourceAsStream(String)}. */
    public String schemaResource() {
        return schemaResource;
    }
}
