package com.github.ifrugal.lifecycle.jdbc;

import org.junit.jupiter.api.AfterAll;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.SQLException;

/** H7 on PostgreSQL, where the optimistic update contends for a real row lock. Skipped without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class PostgresConcurrencyTest extends JdbcConcurrencyTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    private static PooledDataSource pool;

    @AfterAll
    static void closePool() {
        if (pool != null) {
            pool.close();
            pool = null;
        }
    }

    @Override
    protected synchronized DataSource dataSource() {
        if (pool == null) {
            try {
                pool = new PooledDataSource(
                        TestDatabases.postgres(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()), 16);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        return pool;
    }

    @Override
    protected Dialect dialect() {
        return Dialect.POSTGRESQL;
    }
}
