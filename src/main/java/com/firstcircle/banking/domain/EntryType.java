package com.firstcircle.banking.domain;

/**
 * Accounting direction of a ledger entry.
 *
 * <p>We store amounts as signed minor units in {@link LedgerEntry}; the sign is
 * derived from this type: CREDIT is positive (adds to an account's balance),
 * DEBIT is negative (subtracts from it).
 */
public enum EntryType {
    DEBIT,
    CREDIT
}
