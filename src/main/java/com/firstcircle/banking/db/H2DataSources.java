package com.firstcircle.banking.db;

import javax.sql.DataSource;
import org.h2.jdbcx.JdbcConnectionPool;

/**
 * Builds H2 in-memory {@link DataSource}s.
 *
 * <p>{@code DB_CLOSE_DELAY=-1} keeps the in-memory database alive across connections (so the pool's
 * connections share one database). {@code LOCK_TIMEOUT} is set generously so that, under the
 * concurrency stress tests, a contended row lock waits rather than failing fast — canonical lock
 * ordering prevents deadlocks, so a wait always resolves. MVCC (row-level locking for
 * {@code SELECT ... FOR UPDATE}) is on by default in H2 2.x.
 *
 * <p>Uses H2's built-in {@link JdbcConnectionPool}; no connection-pool dependency is required.
 */
public final class H2DataSources {

    private H2DataSources() {
    }

    /**
     * A fresh named in-memory database. Each distinct {@code name} is an isolated database, so tests
     * pass a unique name per fixture for full isolation.
     */
    public static DataSource inMemory(String name) {
        String url = "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=60000";
        return JdbcConnectionPool.create(url, "sa", "");
    }
}
