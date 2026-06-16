package com.firstcircle.banking.exceptions;

import java.util.Currency;

/**
 * Raised when an operation's money currency does not match the account's currency,
 * or when an initial deposit currency does not match the currency requested for the
 * new account.
 */
public class CurrencyMismatchException extends BankingException {

    public CurrencyMismatchException(Currency expected, Currency actual) {
        super("Currency mismatch: expected " + expected.getCurrencyCode()
                + " but got " + actual.getCurrencyCode());
    }
}
