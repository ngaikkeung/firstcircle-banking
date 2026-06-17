package com.firstcircle.banking.db;

/**
 * Unchecked wrapper for {@link java.sql.SQLException}s raised by the JDBC layer. Keeps SQL
 * exceptions out of the domain/service signatures while still surfacing infrastructure failures.
 */
public final class DataAccessException extends RuntimeException {

    public DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    public DataAccessException(String message) {
        super(message);
    }

    public DataAccessException(Throwable cause) {
        super(cause);
    }
}
