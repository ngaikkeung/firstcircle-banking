package com.firstcircle.banking.exceptions;

import com.firstcircle.banking.domain.AccountId;

public class InsufficientFundsException extends BankingException {

    public InsufficientFundsException(AccountId id, long availableMinor, long requestedMinor) {
        super("Insufficient funds on account " + id.getValue()
                + ": available=" + availableMinor + " minor units, requested=" + requestedMinor);
    }
}
