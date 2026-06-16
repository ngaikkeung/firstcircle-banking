package com.firstcircle.banking.exceptions;

import com.firstcircle.banking.idempotency.IdempotencyKey;

/**
 * Raised when an idempotency key is reused for a request whose parameters differ
 * from the request that originally claimed the key. This protects callers from the
 * subtle bug of accidentally returning the wrong prior result.
 */
public class IdempotencyConflictException extends BankingException {

    public IdempotencyConflictException(IdempotencyKey key, String storedFingerprint, String requestedFingerprint) {
        super("Idempotency key " + key.value() + " was already used by a different request "
                + "(stored fingerprint=" + storedFingerprint + ", requested=" + requestedFingerprint + ")");
    }
}
