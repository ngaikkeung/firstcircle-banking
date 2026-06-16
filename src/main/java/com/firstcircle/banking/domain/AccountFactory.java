package com.firstcircle.banking.domain;

import java.util.Currency;
import java.util.Objects;

/**
 * Creates {@link Account} instances with a fresh {@link AccountId}. Centralising this keeps
 * account-construction rules in one place and makes it easy to swap in a different id strategy
 * (e.g. server-assigned sequential ids) later.
 */
public final class AccountFactory {

    private AccountFactory() {
    }

    public static Account newAccount(String ownerName, Currency currency, long initialBalanceMinor) {
        Objects.requireNonNull(ownerName, "ownerName");
        Objects.requireNonNull(currency, "currency");
        return new Account(AccountId.random(), ownerName, currency, initialBalanceMinor);
    }
}
