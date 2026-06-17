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
 * transaction; {@link #findById(AccountId, Connection)} is a non-locking read.
 */
public interface AccountRepository {

    /** Locking read: {@code SELECT ... FOR UPDATE}. The row stays locked until the tx commits/rolls back. */
    Optional<Account> findForUpdate(AccountId id, Connection connection);

    /** Non-locking read. */
    Optional<Account> findById(AccountId id, Connection connection);

    /** Insert a newly-created account. */
    void insert(Account account, Connection connection);

    /** Update an existing account's mutable fields (balance). Id and currency are immutable. */
    void update(Account account, Connection connection);
}
