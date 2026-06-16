package com.firstcircle.banking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstcircle.banking.domain.Account;
import com.firstcircle.banking.domain.AccountId;
import com.firstcircle.banking.domain.EntryType;
import com.firstcircle.banking.domain.Money;
import com.firstcircle.banking.domain.Transaction;
import com.firstcircle.banking.domain.TransactionType;
import com.firstcircle.banking.exceptions.AccountNotFoundException;
import com.firstcircle.banking.exceptions.CurrencyMismatchException;
import com.firstcircle.banking.exceptions.NegativeAmountException;
import com.firstcircle.banking.ledger.ContraAccountIds;
import org.junit.jupiter.api.Test;

class BankingServiceDepositTest {

    private final BankingService bank = TestFixtures.newService();

    private Account newHkdAccount() {
        return bank.createAccount("Acme", TestFixtures.HKD, Money.ofMinor(50_00, TestFixtures.HKD));
    }

    @Test
    void depositIncreasesBalanceAndRecordsBalancedTransaction() {
        Account account = newHkdAccount();
        Money before = bank.getBalance(account.id());

        Transaction tx = bank.deposit(account.id(), Money.ofMinor(25_00, TestFixtures.HKD));

        assertThat(tx.type()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(tx.entries()).hasSize(2);
        assertThat(tx.entries()).anySatisfy(e -> {
            assertThat(e.account()).isEqualTo(account.id());
            assertThat(e.type()).isEqualTo(EntryType.CREDIT);
            assertThat(e.signedAmount()).isEqualTo(2500L);
        });
        assertThat(tx.entries()).anySatisfy(e -> {
            assertThat(e.account()).isEqualTo(ContraAccountIds.CASH_CONTRA);
            assertThat(e.type()).isEqualTo(EntryType.DEBIT);
        });
        assertThat(bank.getBalance(account.id())).isEqualTo(before.plus(Money.ofMinor(25_00, TestFixtures.HKD)));
    }

    @Test
    void rejectsDepositInWrongCurrency() {
        Account account = newHkdAccount();
        assertThatThrownBy(() -> bank.deposit(account.id(), Money.ofMinor(10_00, TestFixtures.USD)))
                .isInstanceOf(CurrencyMismatchException.class);
        // balance untouched
        assertThat(bank.getBalance(account.id())).isEqualTo(Money.ofMinor(50_00, TestFixtures.HKD));
    }

    @Test
    void rejectsDepositToUnknownAccount() {
        assertThatThrownBy(() -> bank.deposit(AccountId.random(), Money.ofMinor(10_00, TestFixtures.HKD)))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void rejectsZeroAndNegativeDeposits() {
        Account account = newHkdAccount();
        assertThatThrownBy(() -> bank.deposit(account.id(), Money.zero(TestFixtures.HKD)))
                .isInstanceOf(NegativeAmountException.class);
    }
}
