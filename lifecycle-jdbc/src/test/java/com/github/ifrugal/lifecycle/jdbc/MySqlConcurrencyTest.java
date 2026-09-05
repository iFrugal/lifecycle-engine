package com.github.ifrugal.lifecycle.jdbc;

import org.junit.jupiter.api.AfterAll;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.sql.SQLException;

/** H7 on MySQL, where the optimistic update contends for a real row lock. Skipped without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class MySqlConcurrencyTest extends JdbcConcurrencyTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

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
                        TestDatabases.mysql(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()), 16);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        return pool;
    }

    @Override
    protected Dialect dialect() {
        return Dialect.MYSQL;
    }
}
