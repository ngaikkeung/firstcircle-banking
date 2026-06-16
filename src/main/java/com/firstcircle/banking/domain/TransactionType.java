package com.firstcircle.banking.domain;

/** Kind of business operation that produced a transaction. */
public enum TransactionType {
    /** A non-zero initial deposit made when an account was opened. */
    CREATE,
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER
}
