package com.github.ifrugal.lifecycle.jdbc;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;

/**
 * The same contract as {@link PostgresStateStoreContractTest}, on MySQL: the module ships {@code
 * schema-mysql.sql} and {@link Dialect#MYSQL}, so this keeps that dialect honest against the real thing.
 * Skipped, not failed, when Docker is absent.
 */
@Testcontainers(disabledWithoutDocker = true)
class MySqlStateStoreContractTest extends JdbcStateStoreContractTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private static DataSource dataSource;

    @Override
    protected synchronized DataSource dataSource() {
        if (dataSource == null) {
            dataSource = TestDatabases.mysql(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        }
        return dataSource;
    }

    @Override
    protected Dialect dialect() {
        return Dialect.MYSQL;
    }
}
