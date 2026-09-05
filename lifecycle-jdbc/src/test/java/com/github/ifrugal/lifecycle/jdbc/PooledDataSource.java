package com.github.ifrugal.lifecycle.jdbc;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * The smallest connection pool that lets the contended tests run: the module itself takes any
 * {@link DataSource} and must never assume one, so the tests supply this rather than pull in a real pool.
 * {@code close()} on a handed-out connection rolls back and returns it to the pool.
 */
final class PooledDataSource implements DataSource, AutoCloseable {

    private final BlockingQueue<Connection> idle;
    private final List<Connection> all = new ArrayList<>();

    PooledDataSource(DataSource delegate, int size) throws SQLException {
        this.idle = new ArrayBlockingQueue<>(size);
        for (int i = 0; i < size; i++) {
            Connection cn = delegate.getConnection();
            all.add(cn);
            idle.add(cn);
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection raw;
        try {
            raw = idle.poll(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("interrupted waiting for a pooled connection", e);
        }
        if (raw == null) {
            throw new SQLException("no pooled connection became available within 60s");
        }
        return (Connection) Proxy.newProxyInstance(
                PooledDataSource.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "close" -> {
                            release(raw);
                            return null;
                        }
                        case "isClosed" -> {
                            return false;
                        }
                        default -> {
                            try {
                                return method.invoke(raw, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        }
                    }
                });
    }

    private void release(Connection raw) {
        try {
            if (!raw.getAutoCommit()) {
                raw.rollback();
                raw.setAutoCommit(true);
            }
        } catch (SQLException ignored) {
            // a broken connection is still returned; the next user will see the failure
        }
        idle.offer(raw);
    }

    @Override
    public void close() {
        for (Connection cn : all) {
            try {
                cn.close();
            } catch (SQLException ignored) {
                // best effort
            }
        }
    }

    @Override public Connection getConnection(String username, String password) throws SQLException { return getConnection(); }
    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter out) { /* unused */ }
    @Override public void setLoginTimeout(int seconds) { /* unused */ }
    @Override public int getLoginTimeout() { return 0; }
    @Override public Logger getParentLogger() { return Logger.getGlobal(); }
    @Override public <T> T unwrap(Class<T> iface) { return iface.cast(this); }
    @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
}
