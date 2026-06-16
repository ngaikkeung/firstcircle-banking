package com.firstcircle.banking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstcircle.banking.domain.Account;
import com.firstcircle.banking.domain.AccountId;
import com.firstcircle.banking.domain.Money;
import com.firstcircle.banking.exceptions.AccountNotFoundException;
import com.firstcircle.banking.exceptions.CurrencyMismatchException;
import com.firstcircle.banking.exceptions.InsufficientFundsException;
import com.firstcircle.banking.exceptions.NegativeAmountException;
import org.junit.jupiter.api.Test;

class BankingServiceWithdrawalTest {

    private final BankingService bank = TestFixtures.newService();

    private Account fundedHkdAccount() {
        return bank.createAccount("Acme", TestFixtures.HKD, Money.ofMinor(100_00, TestFixtures.HKD));
    }

    @Test
    void withdrawalDecreasesBalance() {
        Account account = fundedHkdAccount();
        bank.withdraw(account.id(), Money.ofMinor(30_00, TestFixtures.HKD));
        assertThat(bank.getBalance(account.id())).isEqualTo(Money.ofMinor(70_00, TestFixtures.HKD));
    }

    @Test
    void canWithdrawEntireBalance() {
        Account account = fundedHkdAccount();
        bank.withdraw(account.id(), Money.ofMinor(100_00, TestFixtures.HKD));
        assertThat(bank.getBalance(account.id())).isEqualTo(Money.zero(TestFixtures.HKD));
    }

    @Test
    void overdraftIsNotAllowedAndBalanceIsUnchanged() {
        Account account = fundedHkdAccount();
        Money before = bank.getBalance(account.id());

        assertThatThrownBy(() -> bank.withdraw(account.id(), Money.ofMinor(100_01, TestFixtures.HKD)))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(bank.getBalance(account.id())).isEqualTo(before);
    }

    @Test
    void rejectsWithdrawalInWrongCurrency() {
        Account account = fundedHkdAccount();
        assertThatThrownBy(() -> bank.withdraw(account.id(), Money.ofMinor(10_00, TestFixtures.USD)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void rejectsWithdrawalFromUnknownAccount() {
        assertThatThrownBy(() -> bank.withdraw(AccountId.random(), Money.ofMinor(10_00, TestFixtures.HKD)))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void rejectsZeroWithdrawal() {
        Account account = fundedHkdAccount();
        assertThatThrownBy(() -> bank.withdraw(account.id(), Money.zero(TestFixtures.HKD)))
                .isInstanceOf(NegativeAmountException.class);
    }
}
