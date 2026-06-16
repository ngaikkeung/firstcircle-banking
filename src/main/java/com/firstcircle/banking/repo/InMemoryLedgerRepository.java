package com.firstcircle.banking.repo;

import com.firstcircle.banking.domain.AccountId;
import com.firstcircle.banking.domain.LedgerEntry;
import com.firstcircle.banking.domain.Transaction;
import com.firstcircle.banking.domain.TransactionId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe in-memory {@link LedgerRepository}. Transactions are stored in an append-only
 * {@link CopyOnWriteArrayList} (append is atomic) and indexed by id for fast lookup.
 */
public final class InMemoryLedgerRepository implements LedgerRepository {

    private final List<Transaction> transactions = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<TransactionId, Transaction> byId = new ConcurrentHashMap<>();

    @Override
    public void append(Transaction transaction) {
        transactions.add(transaction); // CopyOnWriteArrayList add is atomic
        byId.put(transaction.id(), transaction);
    }

    @Override
    public Optional<Transaction> findById(TransactionId id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<Transaction> findAll() {
        return List.copyOf(transactions);
    }

    @Override
    public List<LedgerEntry> entriesFor(AccountId id) {
        List<LedgerEntry> result = new ArrayList<>();
        for (Transaction tx : transactions) {
            for (LedgerEntry entry : tx.entries()) {
                if (entry.account().equals(id)) {
                    result.add(entry);
                }
            }
        }
        return result;
    }
}
