package com.github.ifrugal.lifecycle.jdbc;


import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;

/**
 * The same contract on the database the module is actually meant for. Skipped, not failed, when Docker is
 * absent: H2 keeps the contract honest in that case, and PostgreSQL confirms it on the real thing in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresStateStoreContractTest extends JdbcStateStoreContractTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    private static DataSource dataSource;

    @Override
    protected synchronized DataSource dataSource() {
        if (dataSource == null) {
            dataSource = TestDatabases.postgres(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        }
        return dataSource;
    }

    @Override
    protected Dialect dialect() {
        return Dialect.POSTGRESQL;
    }
}
