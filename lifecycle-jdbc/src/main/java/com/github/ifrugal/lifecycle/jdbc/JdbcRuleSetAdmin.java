package com.github.ifrugal.lifecycle.jdbc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * The smallest useful governance surface over {@code lifecycle_rule_set} (DD-05): draft, activate, retire.
 * Approval, diffing and rollback policy are deliberately not here — rollback is just activating an older
 * version, and {@code RuleCompiler.compile(...).problems()} is public so a governance UI can validate a draft
 * before it activates it.
 */
public final class JdbcRuleSetAdmin {

    private static final Logger log = LoggerFactory.getLogger(JdbcRuleSetAdmin.class);

    private final DataSource dataSource;
    private final Clock clock;

    public JdbcRuleSetAdmin(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    public JdbcRuleSetAdmin(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** @param tenantId null for a base rule set @return the generated row id */
    public long insertDraft(String tenantId, String entityType, int version, String format, String body, String createdBy) {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(body, "body");
        try (Connection cn = dataSource.getConnection();
             PreparedStatement ps = cn.prepareStatement(Sql.INSERT_RULE_SET_DRAFT, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, tenantId);
            ps.setString(2, entityType);
            ps.setInt(3, version);
            ps.setString(4, format);
            ps.setString(5, body);
            ps.setString(6, createdBy == null ? "" : createdBy);
            Jdbc.setInstant(ps, 7, clock.instant());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new IllegalStateException("the database returned no generated key for the new rule set draft");
                }
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new JdbcStoreException("cannot insert a draft rule set for " + entityType, e);
        }
    }

    /**
     * Retires whatever is ACTIVE for this row's (tenant, entity type) and makes this row ACTIVE, in one
     * transaction, so the unique index never sees two ACTIVE rows and the registry never sees zero.
     */
    public void activate(long id) {
        try (Connection cn = dataSource.getConnection()) {
            boolean autoCommit = cn.getAutoCommit();
            cn.setAutoCommit(false);
            try {
                String tenantId;
                String entityType;
                try (PreparedStatement ps = cn.prepareStatement(Sql.SELECT_RULE_SET_KEY)) {
                    ps.setLong(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalArgumentException("no rule set with id " + id);
                        }
                        tenantId = rs.getString("tenant_id");
                        entityType = rs.getString("entity_type");
                    }
                }
                try (PreparedStatement ps = cn.prepareStatement(Sql.RETIRE_ACTIVE_RULE_SET)) {
                    ps.setString(1, entityType);
                    ps.setString(2, Jdbc.tenantKey(tenantId));
                    ps.executeUpdate();
                }
                Instant now = clock.instant();
                try (PreparedStatement ps = cn.prepareStatement(Sql.ACTIVATE_RULE_SET)) {
                    Jdbc.setInstant(ps, 1, now);
                    ps.setLong(2, id);
                    ps.executeUpdate();
                }
                cn.commit();
                log.info("rule set {} ({} / {}) is now ACTIVE", id, tenantId == null ? "base" : tenantId, entityType);
            } catch (SQLException | RuntimeException e) {
                Jdbc.rollbackQuietly(cn);
                throw e;
            } finally {
                if (autoCommit) {
                    cn.setAutoCommit(true);
                }
            }
        } catch (SQLException e) {
            throw new JdbcStoreException("cannot activate rule set " + id, e);
        }
    }

    public void retire(long id) {
        try (Connection cn = dataSource.getConnection(); PreparedStatement ps = cn.prepareStatement(Sql.RETIRE_RULE_SET)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new JdbcStoreException("cannot retire rule set " + id, e);
        }
    }
}
