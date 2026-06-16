package com.firstcircle.banking.exceptions;

/**
 * Raised for any amount that must be strictly positive but was zero or negative
 * (deposits, withdrawals, transfers), or for a negative initial deposit.
 */
public class NegativeAmountException extends BankingException {

    public NegativeAmountException(String message) {
        super(message);
    }
}
