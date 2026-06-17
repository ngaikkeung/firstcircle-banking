package com.firstcircle.banking.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sql.DataSource;

/**
 * Runs a unit of work in a single database transaction.
 *
 * <p>This is where multi-account atomicity lives: it is provided by the transaction, not by JVM
 * locks. Every mutating banking operation runs inside
 * {@link #run(SqlAction)}: the {@link Connection} is opened with {@code autoCommit=false}, the
 * action executes against it (issuing {@code SELECT ... FOR UPDATE} to lock rows in canonical
 * account-id order), and on normal return the transaction is {@link Connection#commit() committed}.
 * Any {@link RuntimeException} (e.g. {@code InsufficientFundsException}) {@link Connection#rollback()
 * rolls back} the whole operation and propagates, so a failed transfer leaves no balance change and
 * no ledger row.
 *
 * <p>Repositories convert {@link SQLException}s to {@link DataAccessException} /
 * {@link UniqueViolationException}, so {@link SqlAction} throws no checked exceptions and service
 * code stays clean.
 */
public final class TransactionManager {

    private final DataSource dataSource;

    public TransactionManager(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** DataSource in use (read-only consumers, e.g. schema init, may need it). */
    public DataSource dataSource() {
        return dataSource;
    }

    /**
     * Run {@code action} in a transaction; commit on normal return, rollback on any
     * {@link RuntimeException}.
     */
    public <T> T run(SqlAction<T> action) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                T result = action.apply(c);
                c.commit();
                return result;
            } catch (RuntimeException e) {
                rollbackQuietly(c);
                throw e;
            }
        } catch (SQLException e) {
            // getConnection / setAutoCommit / commit / close
            throw new DataAccessException("transaction failed", e);
        }
    }

    /** Void variant of {@link #run(SqlAction)}. */
    public void runVoid(SqlRunnable action) {
        run(c -> {
            action.run(c);
            return null;
        });
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignore) {
            // best-effort; the original exception is already propagating
        }
    }

    /** Like {@link Function}, but receives a {@link Connection} and throws no checked exceptions. */
    @FunctionalInterface
    public interface SqlAction<T> {
        T apply(Connection connection);
    }

    /** Like {@link Runnable}, but receives a {@link Connection}. */
    @FunctionalInterface
    public interface SqlRunnable {
        void run(Connection connection);
    }
}
