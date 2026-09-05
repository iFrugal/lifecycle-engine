package com.github.ifrugal.lifecycle.jdbc;

import java.sql.SQLException;

/** An unrecoverable database failure. Checked {@link SQLException}s never leak out of this module's SPI methods. */
public final class JdbcStoreException extends RuntimeException {

    public JdbcStoreException(String message, SQLException cause) {
        super(message + ": " + cause.getMessage() + " [state " + cause.getSQLState() + ", code " + cause.getErrorCode() + "]", cause);
    }
}
