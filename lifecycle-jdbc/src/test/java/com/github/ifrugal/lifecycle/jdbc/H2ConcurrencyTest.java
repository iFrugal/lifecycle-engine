package com.github.ifrugal.lifecycle.jdbc;

import org.junit.jupiter.api.AfterAll;

import javax.sql.DataSource;
import java.sql.SQLException;

/** H7 on H2. */
class H2ConcurrencyTest extends JdbcConcurrencyTest {

    private static final PooledDataSource DS;

    static {
        try {
            DS = new PooledDataSource(TestDatabases.h2(), 16);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void closePool() {
        DS.close();
    }

    @Override
    protected DataSource dataSource() {
        return DS;
    }

    @Override
    protected Dialect dialect() {
        return Dialect.H2;
    }
}
