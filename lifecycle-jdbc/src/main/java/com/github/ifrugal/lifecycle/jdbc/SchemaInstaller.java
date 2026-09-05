package com.github.ifrugal.lifecycle.jdbc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs a dialect's DDL. Convenience for tests and small deployments; a real deployment usually hands the same
 * files to Flyway or Liquibase. Every statement is {@code create ... if not exists}, so installing twice is a
 * no-op.
 */
public final class SchemaInstaller {

    private static final Logger log = LoggerFactory.getLogger(SchemaInstaller.class);

    private SchemaInstaller() {}

    public static void install(DataSource dataSource, Dialect dialect) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(dialect, "dialect");
        List<String> statements = statements(dialect);
        try (Connection cn = dataSource.getConnection(); Statement st = cn.createStatement()) {
            for (String sql : statements) {
                st.execute(sql);
            }
            log.debug("installed {} schema statements for {}", statements.size(), dialect);
        } catch (SQLException e) {
            throw new JdbcStoreException("cannot install the " + dialect + " schema", e);
        }
    }

    /** The DDL split into executable statements: {@code --} comments stripped, then split on {@code ;}. */
    public static List<String> statements(Dialect dialect) {
        String ddl = read(dialect.schemaResource());
        StringBuilder stripped = new StringBuilder(ddl.length());
        for (String line : ddl.split("\n", -1)) {
            int comment = line.indexOf("--");
            stripped.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }
        List<String> out = new ArrayList<>();
        for (String part : stripped.toString().split(";")) {
            String sql = part.strip();
            if (!sql.isEmpty()) {
                out.add(sql);
            }
        }
        return List.copyOf(out);
    }

    private static String read(String resource) {
        ClassLoader loader = SchemaInstaller.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("schema resource not on the classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + resource, e);
        }
    }
}
