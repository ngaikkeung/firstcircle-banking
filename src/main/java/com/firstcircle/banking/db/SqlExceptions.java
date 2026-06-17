package com.firstcircle.banking.db;

import java.sql.SQLException;

/**
 * Translates {@link SQLException}s into the right unchecked type: a UNIQUE / primary-key violation
 * (SQLSTATE {@code 23505}) becomes {@link UniqueViolationException} (so callers can treat a lost
 * idempotency-key race specially); anything else becomes {@link DataAccessException}.
 */
public final class SqlExceptions {

    private SqlExceptions() {
    }

    public static RuntimeException wrap(SQLException e) {
        return "23505".equals(e.getSQLState()) ? new UniqueViolationException(e) : new DataAccessException(e);
    }
}
