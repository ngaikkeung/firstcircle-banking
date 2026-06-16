package com.firstcircle.banking.repo;

import com.firstcircle.banking.domain.AccountId;
import com.firstcircle.banking.domain.LedgerEntry;
import com.firstcircle.banking.domain.Transaction;
import com.firstcircle.banking.domain.TransactionId;
import java.util.List;
import java.util.Optional;

/**
 * Port for the append-only ledger. Implementations must make {@link #append(Transaction)}
 * atomic so a transaction is never observable half-written.
 */
public interface LedgerRepository {

    void append(Transaction transaction);

    Optional<Transaction> findById(TransactionId id);

    List<Transaction> findAll();

    /** Every ledger entry that touches the given account, across all transactions, in order. */
    List<LedgerEntry> entriesFor(AccountId id);
}
