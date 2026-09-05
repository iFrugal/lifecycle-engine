package com.github.ifrugal.lifecycle.jdbc;

import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.spi.Outbox;
import com.github.ifrugal.lifecycle.json.LifecycleJson;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The rows {@link JdbcStateStore#commit} wrote and nobody has confirmed published yet (DD-07). Rows are kept
 * after sending, with {@code sent_at} set, so the table doubles as a record of what left the process; prune it
 * on whatever schedule the deployment wants.
 */
public final class JdbcOutbox implements Outbox {

    private final DataSource dataSource;
    private final Clock clock;

    public JdbcOutbox(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    public JdbcOutbox(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<LifecycleEvent> unsent(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<LifecycleEvent> out = new ArrayList<>();
        try (Connection cn = dataSource.getConnection(); PreparedStatement ps = cn.prepareStatement(Sql.SELECT_UNSENT_OUTBOX)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(LifecycleJson.readEvent(rs.getString("body")));
                }
            }
        } catch (SQLException e) {
            throw new JdbcStoreException("cannot read the outbox", e);
        }
        return List.copyOf(out);
    }

    @Override
    public void markSent(Collection<String> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) {
            return;
        }
        try (Connection cn = dataSource.getConnection(); PreparedStatement ps = cn.prepareStatement(Sql.MARK_OUTBOX_SENT)) {
            for (String id : eventIds) {
                Jdbc.setInstant(ps, 1, clock.instant());
                ps.setString(2, id);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new JdbcStoreException("cannot mark " + eventIds.size() + " outbox row(s) sent", e);
        }
    }
}
