package com.firstcircle.banking.db;

/**
 * Thrown when a JDBC insert hits a UNIQUE / PRIMARY-KEY constraint (SQLSTATE {@code 23505}).
 *
 * <p>Used by idempotency: inserting a request key that another transaction already stored (in the
 * same instant) violates the key's {@code UNIQUE} constraint, signalling a lost race — the caller
 * rolls back and returns the winner's stored result instead.
 */
public final class UniqueViolationException extends RuntimeException {

    public UniqueViolationException(Throwable cause) {
        super(cause);
    }
}
