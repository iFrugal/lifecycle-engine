package com.github.ifrugal.lifecycle.jdbc;

import com.github.ifrugal.lifecycle.json.LifecycleJson;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Parameter binding, result mapping and vendor error classification, shared by every store in this module. */
final class Jdbc {

    /** MySQL's duplicate-key error number; its SQLState is the generic integrity-violation class 23000. */
    private static final int MYSQL_DUPLICATE_ENTRY = 1062;
    /** H2: "Concurrent update in table: another transaction has updated or deleted the same row". */
    private static final int H2_CONCURRENT_UPDATE = 90131;
    /** H2: "Timeout trying to lock table". */
    private static final int H2_LOCK_TIMEOUT = 50200;
    /** A driver may chain both {@code getNextException} and {@code getCause}; this bounds the walk. */
    private static final int MAX_CAUSE_DEPTH = 8;

    private Jdbc() {}

    // ------------------------------------------------------------- binding

    static void setInstant(PreparedStatement ps, int index, Instant value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            ps.setObject(index, OffsetDateTime.ofInstant(value, ZoneOffset.UTC));
        }
    }

    static Instant getInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    /** {@code null} is stored as {@code ''} in the state and inbox keys so a missing tenant cannot collide (DD-11). */
    static String tenantKey(String tenantId) {
        return tenantId == null ? "" : tenantId;
    }

    /** The inverse of {@link #tenantKey}: an empty key column is the absent tenant. */
    static String tenantOrNull(String stored) {
        return stored == null || stored.isEmpty() ? null : stored;
    }

    static String writeRoles(Set<String> roles) {
        return LifecycleJson.writeAny(roles == null ? Set.of() : new LinkedHashSet<>(roles));
    }

    static Set<String> readRoles(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        return Set.of(LifecycleJson.read(json, String[].class));
    }

    // ------------------------------------------------------------- errors

    /** True if this failure (or anything it wraps) is a unique or primary key violation. */
    static boolean isUniqueViolation(SQLException e) {
        for (SQLException cur : chain(e)) {
            String state = cur.getSQLState();
            if ("23505".equals(state)) {
                return true;
            }
            if (("23000".equals(state) || "23001".equals(state)) && cur.getErrorCode() == MYSQL_DUPLICATE_ENTRY) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if the database refused the write because another transaction touched the same row. PostgreSQL never
     * reports this at read committed (it blocks and then the {@code where version = ?} simply matches nothing),
     * but H2's MVStore does, and a lock timeout means the same thing: we lost the race. Reported to the caller
     * as a {@code VersionMismatch}, which is what it is.
     */
    static boolean isConcurrentUpdate(SQLException e) {
        for (SQLException cur : chain(e)) {
            String state = cur.getSQLState();
            int code = cur.getErrorCode();
            if ("40001".equals(state) || "40P01".equals(state) || "HYT00".equals(state)) {
                return true;
            }
            if (code == H2_CONCURRENT_UPDATE || code == H2_LOCK_TIMEOUT) {
                return true;
            }
        }
        return false;
    }

    /** The failure and whatever it wraps, at most {@value #MAX_CAUSE_DEPTH} deep so a cyclic chain cannot spin. */
    private static List<SQLException> chain(SQLException e) {
        List<SQLException> out = new ArrayList<>();
        SQLException cur = e;
        while (cur != null && out.size() < MAX_CAUSE_DEPTH && !out.contains(cur)) {
            out.add(cur);
            SQLException next = cur.getNextException();
            cur = next != null ? next : (cur.getCause() instanceof SQLException c ? c : null);
        }
        return out;
    }

    // ------------------------------------------------------------- transactions

    static void rollbackQuietly(Connection cn) {
        try {
            cn.rollback();
        } catch (SQLException ignored) {
            // the transaction is already gone; nothing was written either way
        }
    }
}
