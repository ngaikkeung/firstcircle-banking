package com.firstcircle.banking.exceptions;

/**
 * Base class for all banking-domain failures. Unchecked so the public API of
 * {@code BankingService} stays clean; callers that care can catch the specific
 * subclasses (or this base) and handle them.
 */
public class BankingException extends RuntimeException {

    public BankingException(String message) {
        super(message);
    }

    public BankingException(String message, Throwable cause) {
        super(message, cause);
    }
}
