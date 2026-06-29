package com.firstcircle.banking.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

/**
 * An immutable, double-entry transaction: a set of {@link LedgerEntry}s that all posted
 * atomically.
 *
 * <p><b>Core invariant:</b> the entries must net to zero <em>per currency</em>. This is what
 * makes the ledger conserving and auditable: no money is ever created or destroyed within a
 * transaction — for every currency, what leaves one account enters another (or a contra
 * account). The factory validates this on construction, so an unbalanced transaction cannot
 * exist in the system.
 */
@Getter
@ToString
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class Transaction {

    @NonNull
    private final TransactionId id;
    private final long sequence;
    @NonNull
    private final Instant timestamp;
    @NonNull
    private final TransactionType type;
    @NonNull
    private final List<LedgerEntry> entries;

    /**
     * Create a transaction, validating the per-currency balance invariant.
     *
     * @throws IllegalStateException if any currency's entries do not sum to zero
     *                               (this is a programmer error, never a user-facing condition)
     */
    public static Transaction create(TransactionId id, long sequence, Instant timestamp,
                                     TransactionType type, List<LedgerEntry> entries) {
        Map<Currency, Long> sumsByCurrency = new HashMap<>();
        for (LedgerEntry entry : entries) {
            sumsByCurrency.merge(entry.getCurrency(), entry.getSignedAmount(), Long::sum);
        }
        for (Map.Entry<Currency, Long> e : sumsByCurrency.entrySet()) {
            if (e.getValue() != 0L) {
                throw new IllegalStateException(
                        "Unbalanced transaction: " + e.getValue() + " " + e.getKey().getCurrencyCode()
                                + " does not net to zero (entries=" + entries + ")");
            }
        }
        return new Transaction(id, sequence, timestamp, type, List.copyOf(entries));
    }

    /** Entries that touch the given account, in entry order. */
    public List<LedgerEntry> entriesFor(AccountId account) {
        List<LedgerEntry> result = new ArrayList<>();
        for (LedgerEntry entry : entries) {
            if (entry.getAccount().equals(account)) {
                result.add(entry);
            }
        }
        return result;
    }
}
