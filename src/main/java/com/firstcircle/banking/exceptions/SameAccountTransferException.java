package com.firstcircle.banking.exceptions;

public class SameAccountTransferException extends BankingException {

    public SameAccountTransferException() {
        super("Cannot transfer money from an account to itself");
    }
}
