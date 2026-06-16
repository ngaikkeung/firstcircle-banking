package com.firstcircle.banking.domain;

import java.util.Currency;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

/**
 * A single signed movement against one account, in one currency.
 *
 * <p>Use the {@link #credit(AccountId, Currency, long)} and {@link #debit(AccountId, Currency, long)}
 * factories: they take a positive magnitude and derive the sign and {@link EntryType}, which
 * removes the most common source of double-entry bugs (mismatched sign vs. type).
 *
 * <p>{@code signedAmount} is in minor units and carries the sign: positive for CREDIT,
 * negative for DEBIT.
 */
@Getter
@EqualsAndHashCode
@ToString
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class LedgerEntry {

    @NonNull
    private final AccountId account;
    @NonNull
    private final Currency currency;
    private final long signedAmount;
    @NonNull
    private final EntryType type;

    /** Adds {@code amount} (a positive magnitude) to the account. */
    public static LedgerEntry credit(AccountId account, Currency currency, long amount) {
        requirePositiveMagnitude(amount);
        return new LedgerEntry(account, currency, amount, EntryType.CREDIT);
    }

    /** Subtracts {@code amount} (a positive magnitude) from the account. */
    public static LedgerEntry debit(AccountId account, Currency currency, long amount) {
        requirePositiveMagnitude(amount);
        return new LedgerEntry(account, currency, -amount, EntryType.DEBIT);
    }

    private static void requirePositiveMagnitude(long amount) {
        if (amount <= 0L) {
            throw new IllegalArgumentException("Entry magnitude must be positive: " + amount);
        }
    }
}
