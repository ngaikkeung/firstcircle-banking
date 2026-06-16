package com.firstcircle.banking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstcircle.banking.domain.Account;
import com.firstcircle.banking.domain.Money;
import com.firstcircle.banking.exceptions.CurrencyMismatchException;
import com.firstcircle.banking.exceptions.NegativeAmountException;
import org.junit.jupiter.api.Test;

class BankingServiceCreationTest {

    private final BankingService bank = TestFixtures.newService();

    @Test
    void createsAccountWithInitialDeposit() {
        Account account = bank.createAccount("Acme", TestFixtures.HKD, Money.ofMinor(100_00, TestFixtures.HKD));

        assertThat(account.ownerName()).isEqualTo("Acme");
        assertThat(account.currency()).isEqualTo(TestFixtures.HKD);
        assertThat(bank.getBalance(account.id())).isEqualTo(Money.ofMinor(100_00, TestFixtures.HKD));
    }

    @Test
    void trimsOwnerName() {
        Account account = bank.createAccount("  Spaced Co  ", TestFixtures.HKD, Money.zero(TestFixtures.HKD));
        assertThat(account.ownerName()).isEqualTo("Spaced Co");
    }

    @Test
    void initialDepositCurrencyMustMatchAccountCurrency() {
        assertThatThrownBy(() -> bank.createAccount("Acme", TestFixtures.HKD, Money.ofMinor(100, TestFixtures.USD)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void zeroInitialDepositIsAllowed() {
        Account account = bank.createAccount("Acme", TestFixtures.HKD, Money.zero(TestFixtures.HKD));
        assertThat(bank.getBalance(account.id())).isEqualTo(Money.zero(TestFixtures.HKD));
    }

    @Test
    void negativeInitialDepositIsRejected() {
        // Negative money cannot even be constructed, so creation fails before reaching the service.
        assertThatThrownBy(() -> Money.ofMinor(-1, TestFixtures.HKD))
                .isInstanceOf(NegativeAmountException.class);
    }
}
