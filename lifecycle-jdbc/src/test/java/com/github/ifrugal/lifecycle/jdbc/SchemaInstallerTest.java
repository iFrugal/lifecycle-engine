package com.github.ifrugal.lifecycle.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Every dialect ships DDL that parses into statements, and installing twice is a no-op. */
class SchemaInstallerTest {

    private static final List<String> TABLES = List.of(
            "lifecycle_state", "lifecycle_inbox", "lifecycle_audit", "lifecycle_outbox", "lifecycle_rule_set", "lifecycle_task");

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void everyDialectShipsDdlForEveryTable(Dialect dialect) {
        List<String> statements = SchemaInstaller.statements(dialect);

        assertThat(statements).isNotEmpty();
        assertThat(statements).as("comments are stripped").noneMatch(s -> s.contains("--"));
        for (String table : TABLES) {
            assertThat(statements).as("%s in %s", table, dialect)
                    .anyMatch(s -> s.startsWith("create table if not exists " + table));
        }
    }

    @Test
    void installingTwiceIsANoOp() {
        DataSource ds = TestDatabases.h2();
        SchemaInstaller.install(ds, Dialect.H2);
        SchemaInstaller.install(ds, Dialect.H2);

        for (String table : TABLES) {
            assertThat(TestDatabases.count(ds, table)).isZero();
        }
    }

    @Test
    void theDialectNamesItsOwnResource() {
        for (Dialect dialect : Dialect.values()) {
            assertThat(dialect.schemaResource()).startsWith("db/schema-");
            assertThat(SchemaInstaller.class.getClassLoader().getResource(dialect.schemaResource())).isNotNull();
        }
    }
}
