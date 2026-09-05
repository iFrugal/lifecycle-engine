package com.github.ifrugal.lifecycle.jdbc;

import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/** Database plumbing shared by the JDBC tests. H2 is the default so the suite runs with no Docker. */
final class TestDatabases {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private TestDatabases() {}

    /**
     * A fresh in-memory H2 database per call. {@code DB_CLOSE_DELAY=-1} keeps it alive while a pool cycles
     * connections. The short lock timeout is deliberate: H2's MVStore makes a blocked writer wait in coarse
     * polls, so a long timeout turns the contended test into a sleep. Timing out is the same answer as losing
     * the race, and the store reports both as a VersionMismatch.
     */
    static DataSource h2() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:lifecycle-" + COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=250");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    /** A plain driver DataSource against a running PostgreSQL. */
    static DataSource postgres(String url, String user, String password) {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }

    static void truncateAll(DataSource ds) {
        execute(ds, "delete from lifecycle_state");
        execute(ds, "delete from lifecycle_inbox");
        execute(ds, "delete from lifecycle_audit");
        execute(ds, "delete from lifecycle_outbox");
        execute(ds, "delete from lifecycle_rule_set");
        execute(ds, "delete from lifecycle_task");
    }

    static void execute(DataSource ds, String sql) {
        try (Connection cn = ds.getConnection(); Statement st = cn.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("failed: " + sql, e);
        }
    }

    static long count(DataSource ds, String table) {
        return scalar(ds, "select count(*) from " + table);
    }

    static long scalar(DataSource ds, String sql) {
        try (Connection cn = ds.getConnection(); Statement st = cn.createStatement(); var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("failed: " + sql, e);
        }
    }
}
