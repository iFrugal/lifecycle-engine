package com.github.ifrugal.lifecycle.jdbc;

import javax.sql.DataSource;

/** The commit contract on H2. The default: no Docker, no container, runs everywhere. */
class H2StateStoreContractTest extends JdbcStateStoreContractTest {

    private static final DataSource DS = TestDatabases.h2();

    @Override
    protected DataSource dataSource() {
        return DS;
    }

    @Override
    protected Dialect dialect() {
        return Dialect.H2;
    }
}
