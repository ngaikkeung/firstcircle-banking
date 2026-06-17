package com.firstcircle.banking.repo;

import com.firstcircle.banking.domain.AccountId;
import com.firstcircle.banking.domain.LedgerEntry;
import com.firstcircle.banking.domain.Transaction;
import com.firstcircle.banking.domain.TransactionId;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;

/**
 * Port for the append-only ledger.
 *
 * <p>{@link #append(Transaction, Connection)} inserts the transaction and all its entries within the
 * caller's transaction, so a ledger posting is never observable half-written. The read methods also
 * take the caller's {@link Connection} so an in-flight operation can load a previously-stored
 * transaction (e.g. an idempotent replay).
 */
public interface LedgerRepository {

    void append(Transaction transaction, Connection connection);

    Optional<Transaction> findById(TransactionId id, Connection connection);

    List<Transaction> findAll(Connection connection);

    /** Every ledger entry that touches the given account, across all transactions, in order. */
    List<LedgerEntry> entriesFor(AccountId id, Connection connection);
}
