package com.github.ifrugal.lifecycle.jdbc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code schema-mysql.sql} has no partial index, so "at most one ACTIVE rule set per (tenant, type)" is
 * enforced with a generated column ({@code active_key}, null unless {@code status = 'ACTIVE'}) plus a unique
 * key on it. This proves the database itself refuses a second ACTIVE row - independent of and in addition to
 * {@link JdbcRuleSetAdmin}'s retire-then-activate choreography, which never gives the constraint a chance to
 * fire in normal use. Skipped, not failed, when Docker is absent.
 */
@Testcontainers(disabledWithoutDocker = true)
class MySqlRuleSetActiveConstraintTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private static DataSource dataSource;

    @BeforeEach
    void installAndClear() {
        if (dataSource == null) {
            dataSource = TestDatabases.mysql(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        }
        SchemaInstaller.install(dataSource, Dialect.MYSQL);
        TestDatabases.truncateAll(dataSource);
    }

    @Test
    void aSecondActiveRowForTheBaseRuleSetOfTheSameEntityTypeIsRejected() throws SQLException {
        insertRuleSet(null, "order", 1, "ACTIVE");

        assertThatThrownBy(() -> insertRuleSet(null, "order", 2, "ACTIVE"))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(Jdbc.isUniqueViolation(e)).isTrue());

        assertThat(TestDatabases.count(dataSource, "lifecycle_rule_set")).as("the rejected insert wrote nothing").isEqualTo(1);
    }

    @Test
    void aSecondActiveRowForTheSameTenantedEntityTypeIsRejected() throws SQLException {
        insertRuleSet("acme", "order", 1, "ACTIVE");

        assertThatThrownBy(() -> insertRuleSet("acme", "order", 2, "ACTIVE"))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(Jdbc.isUniqueViolation(e)).isTrue());
    }

    @Test
    void aDifferentTenantOrEntityTypeMayAlsoBeActiveAtTheSameTime() throws SQLException {
        insertRuleSet(null, "order", 1, "ACTIVE");
        insertRuleSet("acme", "order", 1, "ACTIVE");
        insertRuleSet(null, "shipment", 1, "ACTIVE");

        assertThat(TestDatabases.count(dataSource, "lifecycle_rule_set")).isEqualTo(3);
    }

    @Test
    void anyNumberOfDraftOrRetiredRowsCoexistForTheSameKeyEvenWithNoActiveRowAtAll() throws SQLException {
        insertRuleSet(null, "order", 1, "DRAFT");
        insertRuleSet(null, "order", 2, "DRAFT");
        insertRuleSet(null, "order", 3, "RETIRED");
        insertRuleSet(null, "order", 4, "RETIRED");

        assertThat(TestDatabases.count(dataSource, "lifecycle_rule_set")).isEqualTo(4);
    }

    @Test
    void draftAndRetiredRowsCoexistAlongsideTheOneActiveRow() throws SQLException {
        insertRuleSet(null, "order", 1, "RETIRED");
        insertRuleSet(null, "order", 2, "ACTIVE");
        insertRuleSet(null, "order", 3, "DRAFT");

        assertThat(TestDatabases.count(dataSource, "lifecycle_rule_set")).isEqualTo(3);
    }

    private void insertRuleSet(String tenantId, String entityType, int version, String status) throws SQLException {
        try (Connection cn = dataSource.getConnection();
             PreparedStatement ps = cn.prepareStatement("""
                     insert into lifecycle_rule_set (tenant_id, entity_type, version, status, format, body, created_by, created_at)
                     values (?, ?, ?, ?, 'yaml', '{}', 'test', ?)""")) {
            ps.setString(1, tenantId);
            ps.setString(2, entityType);
            ps.setInt(3, version);
            ps.setString(4, status);
            Jdbc.setInstant(ps, 5, Instant.now());
            ps.executeUpdate();
        }
    }
}
