package com.firstcircle.banking.exceptions;

import com.firstcircle.banking.domain.AccountId;

public class AccountNotFoundException extends BankingException {

    public AccountNotFoundException(AccountId id) {
        super("Account not found: " + (id == null ? "null" : id.getValue()));
    }
}
