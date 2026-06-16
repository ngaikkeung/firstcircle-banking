package com.firstcircle.banking.domain;

import java.util.Currency;
import lombok.Getter;
import lombok.ToString;

/**
 * A bank account, modelled as an immutable value: the currency is fixed at creation and the
 * balance only ever changes by producing a <em>new</em> {@code Account} via
 * {@link #credit(long)} / {@link #debit(long)}.
 *
 * <p>Because {@code Account} is immutable, it is trivially safe to publish and read. The
 * per-account lock from {@code LockManager} serialises the <em>read-modify-write</em> of the
 * account's repository entry: while a thread holds an account's lock it can load the current
 * {@code Account}, compute a credited/debited copy, and store it back with no other thread
 * able to interleave. The lock is keyed by the stable {@link AccountId}, not the object
 * reference, so replacing the instance each update is safe.
 */
@Getter
@ToString
public final class Account {

    private final AccountId id;
    private final String ownerName;
    private final Currency currency;
    private long balanceMinor;

    public Account(AccountId id, String ownerName, Currency currency, long balanceMinor) {
        this.id = id;
        String trimmed = ownerName == null ? "" : ownerName.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("ownerName must not be blank");
        }
        this.ownerName = trimmed;
        this.currency = currency;
        if (currency == null) {
            throw new IllegalArgumentException("currency must not be null");
        }
        if (balanceMinor < 0L) {
            throw new IllegalArgumentException("balance must be non-negative");
        }
        this.balanceMinor = balanceMinor;
    }

    /** Current balance as {@link Money}. */
    public Money balance() {
        return Money.ofMinor(balanceMinor, currency);
    }

    /** Return a copy of this account with {@code minorUnits} added. */
    public Account credit(long minorUnits) {
        if (minorUnits < 0L) {
            throw new IllegalArgumentException("credit amount must be non-negative");
        }
        return new Account(id, ownerName, currency, balanceMinor + minorUnits);
    }

    /** Return a copy of this account with {@code minorUnits} subtracted. */
    public Account debit(long minorUnits) {
        if (minorUnits < 0L) {
            throw new IllegalArgumentException("debit amount must be non-negative");
        }
        return new Account(id, ownerName, currency, balanceMinor - minorUnits);
    }
}
