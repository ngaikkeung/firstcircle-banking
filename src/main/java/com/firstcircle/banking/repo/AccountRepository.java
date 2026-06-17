package com.firstcircle.banking.repo;

import com.firstcircle.banking.domain.Account;
import com.firstcircle.banking.domain.AccountId;
import java.sql.Connection;
import java.util.Optional;

/**
 * Port for account storage.
 *
 * <p><b>Concurrency contract:</b> every method runs against the {@link Connection} the caller
 * supplies, so the reads, writes, and row locks of one banking operation all share a single
 * transaction. Multi-account atomicity is provided by that transaction (plus
 * {@code SELECT ... FOR UPDATE} acquired in canonical {@code AccountId} order inside
 * {@code BankingService}); the repository itself owns no locking.
 *
 * <p>{@link #findForUpdate(AccountId, Connection)} locks the account's row for the duration of the
 * transaction; {@link #findById(AccountId, Connection)} is a non-locking read. {@code requestKey}
 * (nullable) is stored on the row and made {@code UNIQUE} by the schema, so idempotent
 * {@code createAccount} calls are deduped by the constraint.
 */
public interface AccountRepository {

    /** Locking read: {@code SELECT ... FOR UPDATE}. The row stays locked until the tx commits/rolls back. */
    Optional<Account> findForUpdate(AccountId id, Connection connection);

    /** Non-locking read. */
    Optional<Account> findById(AccountId id, Connection connection);

    /**
     * Insert a newly-created account. {@code requestKey} is the idempotency key (or {@code null});
     * the schema's {@code UNIQUE} constraint on it rejects a concurrent duplicate create.
     */
    void insert(Account account, String requestKey, Connection connection);

    /** Load the account previously created under an idempotency {@code requestKey}, if any. */
    Optional<Account> findByRequestKey(String requestKey, Connection connection);

    /** Update an existing account's mutable fields (balance). Id and currency are immutable. */
    void update(Account account, Connection connection);
}
