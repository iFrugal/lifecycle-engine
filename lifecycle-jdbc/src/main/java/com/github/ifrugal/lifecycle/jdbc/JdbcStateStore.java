package com.github.ifrugal.lifecycle.jdbc;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.AuditOutcome;
import com.github.ifrugal.lifecycle.api.model.AuditRecord;
import com.github.ifrugal.lifecycle.api.model.Causation;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.RefusalReason;
import com.github.ifrugal.lifecycle.api.model.StateRecord;
import com.github.ifrugal.lifecycle.api.spi.AuditQuery;
import com.github.ifrugal.lifecycle.api.spi.Commit;
import com.github.ifrugal.lifecycle.api.spi.CommitResult;
import com.github.ifrugal.lifecycle.api.spi.Outbox;
import com.github.ifrugal.lifecycle.api.spi.StateStore;
import com.github.ifrugal.lifecycle.json.LifecycleJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The JDBC storage seam (DD-07, DD-11). {@link #commit} is one transaction on one connection, in the order the
 * contract requires and {@code InMemoryStateStore} demonstrates: inbox, version, state, audit, outbox. Any
 * outcome other than {@code Committed} rolls the whole transaction back, so a rejected commit leaves no inbox,
 * audit or outbox row behind.
 *
 * <p>Plain JDBC on a {@link DataSource}: no pool, no ORM and no framework. The caller owns the pool.
 */
public final class JdbcStateStore implements StateStore, AuditQuery {

    private static final Logger log = LoggerFactory.getLogger(JdbcStateStore.class);

    /** DD-07: an inbox row outlives any plausible redelivery, then natural idempotence takes over. */
    public static final Duration DEFAULT_INBOX_RETENTION = Duration.ofDays(7);

    private final DataSource dataSource;
    private final Dialect dialect;
    private final Duration inboxRetention;
    private final Clock clock;
    private final JdbcOutbox outbox;

    public JdbcStateStore(DataSource dataSource, Dialect dialect) {
        this(dataSource, dialect, DEFAULT_INBOX_RETENTION, Clock.systemUTC());
    }

    public JdbcStateStore(DataSource dataSource, Dialect dialect, Duration inboxRetention) {
        this(dataSource, dialect, inboxRetention, Clock.systemUTC());
    }

    public JdbcStateStore(DataSource dataSource, Dialect dialect, Duration inboxRetention, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.inboxRetention = Objects.requireNonNull(inboxRetention, "inboxRetention");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.outbox = new JdbcOutbox(dataSource, clock);
    }

    public Dialect dialect() {
        return dialect;
    }

    // ---------------------------------------------------------------- StateStore

    @Override
    public Optional<StateRecord> find(EntityRef ref) {
        Objects.requireNonNull(ref, "ref");
        try (Connection cn = dataSource.getConnection(); PreparedStatement ps = cn.prepareStatement(Sql.SELECT_STATE)) {
            ps.setString(1, Jdbc.tenantKey(ref.tenantId()));
            ps.setString(2, ref.type());
            ps.setString(3, ref.id());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readState(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new JdbcStoreException("cannot read the state of " + ref.key(), e);
        }
    }

    @Override
    public CommitResult commit(Commit c) {
        Objects.requireNonNull(c, "commit");
        Instant now = clock.instant();
        try (Connection cn = dataSource.getConnection()) {
            boolean autoCommit = cn.getAutoCommit();
            cn.setAutoCommit(false);
            try {
                CommitResult result = doCommit(cn, c, now);
                if (result instanceof CommitResult.Committed) {
                    cn.commit();
                }
                return result;
            } catch (SQLException | RuntimeException e) {
                Jdbc.rollbackQuietly(cn);
                throw e;
            } finally {
                restoreAutoCommit(cn, autoCommit);
            }
        } catch (SQLException e) {
            throw new JdbcStoreException("cannot commit " + c.eventId() + " on " + c.ref().key(), e);
        }
    }

    /** Leaves the connection in a rolled-back state for every result that is not {@link CommitResult.Committed}. */
    private CommitResult doCommit(Connection cn, Commit c, Instant now) throws SQLException {
        // (a) inbox first: a replay is a replay regardless of version (DD-07).
        try (PreparedStatement ps = cn.prepareStatement(Sql.INSERT_INBOX)) {
            ps.setString(1, Jdbc.tenantKey(c.ref().tenantId()));
            ps.setString(2, c.ref().type());
            ps.setString(3, c.ref().id());
            ps.setString(4, c.eventId());
            ps.setString(5, c.audit().auditId());
            Jdbc.setInstant(ps, 6, now.plus(inboxRetention));
            ps.executeUpdate();
        } catch (SQLException e) {
            if (!Jdbc.isUniqueViolation(e)) {
                throw e;
            }
            Jdbc.rollbackQuietly(cn);
            String first = firstAuditId(cn, c);
            return new CommitResult.AlreadyApplied(first);
        }

        // (b) version, and the state write that goes with it.
        long version;
        if (c.advances()) {
            if (c.expectedVersion() == 0L) {
                // Version 0 is the entity the store has never seen: there must be no row yet.
                if (!insertState(cn, c, now)) {
                    Jdbc.rollbackQuietly(cn);
                    return new CommitResult.VersionMismatch(0L, currentVersion(cn, c.ref()));
                }
                version = 1L;
            } else {
                Integer rows = updateState(cn, c, now);
                if (rows == null || rows == 0) {
                    Jdbc.rollbackQuietly(cn);
                    return new CommitResult.VersionMismatch(c.expectedVersion(), currentVersion(cn, c.ref()));
                }
                version = c.expectedVersion() + 1;
            }
        } else {
            // A refusal changes nothing, but it still has to have been decided against the current version.
            long actual = currentVersion(cn, c.ref());
            if (actual != c.expectedVersion()) {
                Jdbc.rollbackQuietly(cn);
                return new CommitResult.VersionMismatch(c.expectedVersion(), actual);
            }
            version = actual;
        }

        // (c) audit, (d) outbox.
        insertAudit(cn, c.audit());
        insertOutbox(cn, c.outbox(), now);
        return new CommitResult.Committed(version, c.audit().auditId());
    }

    /** @return false if the row already exists, which at expected version 0 means somebody created it first */
    private boolean insertState(Connection cn, Commit c, Instant now) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement(Sql.INSERT_STATE)) {
            ps.setString(1, Jdbc.tenantKey(c.ref().tenantId()));
            ps.setString(2, c.ref().type());
            ps.setString(3, c.ref().id());
            ps.setString(4, c.nextState());
            Jdbc.setInstant(ps, 5, now);
            ps.setString(6, c.eventId());
            ps.setString(7, c.audit().ruleSetVersion());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (Jdbc.isUniqueViolation(e) || Jdbc.isConcurrentUpdate(e)) {
                return false;
            }
            throw e;
        }
    }

    /** @return the row count, or null when the database itself reported that we lost the race */
    private Integer updateState(Connection cn, Commit c, Instant now) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement(Sql.UPDATE_STATE)) {
            ps.setString(1, c.nextState());
            Jdbc.setInstant(ps, 2, now);
            ps.setString(3, c.eventId());
            ps.setString(4, c.audit().ruleSetVersion());
            ps.setString(5, Jdbc.tenantKey(c.ref().tenantId()));
            ps.setString(6, c.ref().type());
            ps.setString(7, c.ref().id());
            ps.setLong(8, c.expectedVersion());
            return ps.executeUpdate();
        } catch (SQLException e) {
            if (Jdbc.isConcurrentUpdate(e)) {
                return null;
            }
            throw e;
        }
    }

    private long currentVersion(Connection cn, EntityRef ref) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement(Sql.SELECT_STATE_VERSION)) {
            ps.setString(1, Jdbc.tenantKey(ref.tenantId()));
            ps.setString(2, ref.type());
            ps.setString(3, ref.id());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private String firstAuditId(Connection cn, Commit c) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement(Sql.SELECT_INBOX_AUDIT_ID)) {
            ps.setString(1, Jdbc.tenantKey(c.ref().tenantId()));
            ps.setString(2, c.ref().type());
            ps.setString(3, c.ref().id());
            ps.setString(4, c.eventId());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    @Override
    public void appendDetached(AuditRecord record) {
        Objects.requireNonNull(record, "record");
        try (Connection cn = dataSource.getConnection()) {
            insertAudit(cn, record);
        } catch (SQLException e) {
            throw new JdbcStoreException("cannot append the detached audit row " + record.auditId(), e);
        }
    }

    /** {@code tenantId == null} counts across every tenant, exactly as the in-memory reference store does. */
    @Override
    public OptionalLong countInState(String tenantId, String entityType, String state) {
        String sql = tenantId == null ? Sql.COUNT_IN_STATE_ANY_TENANT : Sql.COUNT_IN_STATE_FOR_TENANT;
        try (Connection cn = dataSource.getConnection(); PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, entityType);
            ps.setString(2, state);
            if (tenantId != null) {
                ps.setString(3, tenantId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.of(0L);
            }
        } catch (SQLException e) {
            throw new JdbcStoreException("cannot count " + entityType + " in " + state, e);
        }
    }

    @Override
    public Optional<Outbox> outbox() {
        return Optional.of(outbox);
    }

    /** The concrete outbox, for a relay that wants it without unwrapping the {@link Optional}. */
    public JdbcOutbox jdbcOutbox() {
        return outbox;
    }

    /** Deletes inbox rows whose retention has elapsed (DD-07). Call it from a scheduler. @return rows deleted */
    public int purgeExpiredInbox() {
        try (Connection cn = dataSource.getConnection(); PreparedStatement ps = cn.prepareStatement(Sql.DELETE_EXPIRED_INBOX)) {
            Jdbc.setInstant(ps, 1, clock.instant());
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                log.debug("purged {} expired inbox row(s)", deleted);
            }
            return deleted;
        } catch (SQLException e) {
            throw new JdbcStoreException("cannot purge the inbox", e);
        }
    }

    // ---------------------------------------------------------------- AuditQuery

    @Override
    public List<AuditRecord> byEntity(EntityRef ref) {
        Objects.requireNonNull(ref, "ref");
        String sql = ref.tenantId() == null ? Sql.SELECT_AUDIT_BY_ENTITY_NO_TENANT : Sql.SELECT_AUDIT_BY_ENTITY;
        try (Connection cn = dataSource.getConnection(); PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, ref.type());
            ps.setString(2, ref.id());
            if (ref.tenantId() != null) {
                ps.setString(3, ref.tenantId());
            }
            return readAudit(ps);
        } catch (SQLException e) {
            throw new JdbcStoreException("cannot read the audit of " + ref.key(), e);
        }
    }

    @Override
    public Optional<AuditRecord> byEventId(String eventId) {
        try (Connection cn = dataSource.getConnection(); PreparedStatement ps = cn.prepareStatement(Sql.SELECT_AUDIT_BY_EVENT)) {
            ps.setString(1, eventId);
            List<AuditRecord> rows = readAudit(ps);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
        } catch (SQLException e) {
            throw new JdbcStoreException("cannot read the audit of event " + eventId, e);
        }
    }

    @Override
    public List<AuditRecord> byCorrelation(String correlationId) {
        try (Connection cn = dataSource.getConnection(); PreparedStatement ps = cn.prepareStatement(Sql.SELECT_AUDIT_BY_CORRELATION)) {
            ps.setString(1, correlationId);
            return readAudit(ps);
        } catch (SQLException e) {
            throw new JdbcStoreException("cannot read the audit of correlation " + correlationId, e);
        }
    }

    // ---------------------------------------------------------------- mapping

    private void insertAudit(Connection cn, AuditRecord a) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement(Sql.INSERT_AUDIT)) {
            Actor actor = a.actor();
            Causation causation = a.causation();
            ps.setString(1, a.auditId());
            ps.setString(2, a.eventId());
            ps.setString(3, a.entity().tenantId());          // NULL, not '': the audit is not keyed by tenant
            ps.setString(4, a.entity().type());
            ps.setString(5, a.entity().id());
            ps.setString(6, a.action());
            ps.setString(7, actor == null ? "" : actor.id());
            ps.setString(8, Jdbc.writeRoles(actor == null ? null : actor.roles()));
            ps.setString(9, actor == null ? "" : actor.kind().name());
            ps.setString(10, a.fromState());
            ps.setString(11, a.toState());
            ps.setString(12, a.transitionId());
            ps.setString(13, a.outcome().name());
            ps.setString(14, a.reason() == null ? null : a.reason().name());
            ps.setString(15, a.detail());
            ps.setString(16, a.ruleSetVersion());
            Jdbc.setInstant(ps, 17, a.at());
            ps.setString(18, causation == null ? a.eventId() : causation.correlationId());
            ps.setString(19, causation == null ? null : causation.causationId());
            ps.setInt(20, causation == null ? 0 : causation.hop());
            ps.executeUpdate();
        }
    }

    private void insertOutbox(Connection cn, List<LifecycleEvent> events, Instant now) throws SQLException {
        if (events.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = cn.prepareStatement(Sql.INSERT_OUTBOX)) {
            for (LifecycleEvent e : events) {
                ps.setString(1, e.eventId());
                ps.setString(2, LifecycleJson.write(e));
                Jdbc.setInstant(ps, 3, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static StateRecord readState(ResultSet rs) throws SQLException {
        EntityRef ref = new EntityRef(Jdbc.tenantOrNull(rs.getString("tenant_id")), rs.getString("entity_type"), rs.getString("entity_id"));
        return new StateRecord(ref, rs.getString("state"), rs.getLong("version"),
                Jdbc.getInstant(rs, "updated_at"), rs.getString("last_event_id"), rs.getString("rule_set_version"));
    }

    private static List<AuditRecord> readAudit(PreparedStatement ps) throws SQLException {
        List<AuditRecord> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(readAuditRow(rs));
            }
        }
        return List.copyOf(out);
    }

    private static AuditRecord readAuditRow(ResultSet rs) throws SQLException {
        EntityRef ref = new EntityRef(rs.getString("tenant_id"), rs.getString("entity_type"), rs.getString("entity_id"));
        String kind = rs.getString("actor_kind");
        Actor actor = kind == null || kind.isEmpty()
                ? null
                : new Actor(rs.getString("actor_id"), Jdbc.readRoles(rs.getString("actor_roles")), Actor.Kind.valueOf(kind));
        String reason = rs.getString("reason");
        Causation causation = new Causation(rs.getString("correlation_id"), rs.getString("causation_id"), rs.getInt("hop"));
        return new AuditRecord(
                rs.getString("audit_id"),
                rs.getString("event_id"),
                ref,
                rs.getString("action"),
                actor,
                rs.getString("from_state"),
                rs.getString("to_state"),
                rs.getString("transition_id"),
                AuditOutcome.valueOf(rs.getString("outcome")),
                reason == null ? null : RefusalReason.valueOf(reason),
                rs.getString("detail"),
                rs.getString("rule_set_version"),
                Jdbc.getInstant(rs, "at"),
                causation);
    }

    private static void restoreAutoCommit(Connection cn, boolean autoCommit) {
        try {
            if (autoCommit) {
                cn.setAutoCommit(true);
            }
        } catch (SQLException ignored) {
            // the connection is on its way back to the pool anyway
        }
    }
}
