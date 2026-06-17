package com.firstcircle.banking.idempotency;

/**
 * What kind of result an idempotent operation produced, so a replay can load the right entity by its
 * stored reference.
 */
public enum IdempotencyResultKind {
    /** The operation returned an {@code Account} (account creation). */
    ACCOUNT,
    /** The operation returned a {@code Transaction} (deposit / withdrawal / transfer). */
    TRANSACTION
}
