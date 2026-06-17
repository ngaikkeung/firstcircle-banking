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
 * <p>{@link #append(Transaction, String, Connection)} inserts the transaction and all its entries
 * within the caller's transaction, so a posting is never observable half-written. {@code requestKey}
 * (nullable) is stored on the transaction row and made {@code UNIQUE} by the schema — that is the
 * idempotency gate for deposit/withdraw/transfer. The read methods also take the caller's connection.
 */
public interface LedgerRepository {

    void append(Transaction transaction, String requestKey, Connection connection);

    Optional<Transaction> findById(TransactionId id, Connection connection);

    /** Load the transaction previously posted under an idempotency {@code requestKey}, if any. */
    Optional<Transaction> findByRequestKey(String requestKey, Connection connection);

    List<Transaction> findAll(Connection connection);

    /** Every ledger entry that touches the given account, across all transactions, in order. */
    List<LedgerEntry> entriesFor(AccountId id, Connection connection);
}
