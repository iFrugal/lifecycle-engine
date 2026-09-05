package com.github.ifrugal.lifecycle.jdbc;

import com.github.ifrugal.lifecycle.api.spi.RuleSetParser;
import com.github.ifrugal.lifecycle.core.registry.StoredRuleSetSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Rule sets stored as documents in {@code lifecycle_rule_set} (DD-05). Only ACTIVE rows are visible to the
 * engine; the schema's unique index guarantees at most one per (tenant, entity type). Drafting and approval are
 * governance and live outside the engine — see {@link JdbcRuleSetAdmin} for the minimum needed to drive it.
 */
public final class JdbcRuleSetSource extends StoredRuleSetSource {

    private final DataSource dataSource;

    public JdbcRuleSetSource(DataSource dataSource, RuleSetParser parser) {
        super(parser);
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    protected List<StoredRuleSet> fetchActive() {
        List<StoredRuleSet> rows = new ArrayList<>();
        try (Connection cn = dataSource.getConnection();
             PreparedStatement ps = cn.prepareStatement(Sql.SELECT_ACTIVE_RULE_SETS);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new StoredRuleSet(
                        rs.getString("tenant_id"),
                        rs.getString("entity_type"),
                        rs.getInt("version"),
                        rs.getString("format"),
                        rs.getString("body")));
            }
        } catch (SQLException e) {
            throw new JdbcStoreException("cannot read the active rule sets", e);
        }
        return List.copyOf(rows);
    }
}
