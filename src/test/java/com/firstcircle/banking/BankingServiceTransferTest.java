package com.firstcircle.banking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstcircle.banking.domain.Account;
import com.firstcircle.banking.domain.AccountId;
import com.firstcircle.banking.domain.Money;
import com.firstcircle.banking.domain.Transaction;
import com.firstcircle.banking.domain.TransactionType;
import com.firstcircle.banking.exceptions.AccountNotFoundException;
import com.firstcircle.banking.exceptions.CurrencyMismatchException;
import com.firstcircle.banking.exceptions.InsufficientFundsException;
import com.firstcircle.banking.exceptions.SameAccountTransferException;
import com.firstcircle.banking.ledger.ContraAccountIds;
import org.junit.jupiter.api.Test;

class BankingServiceTransferTest {

    private final BankingService bank = TestFixtures.newService();

    @Test
    void sameCurrencyTransferMovesFundsExactly() {
        Account a = bank.createAccount("A", TestFixtures.HKD, Money.ofMinor(100_00, TestFixtures.HKD));
        Account b = bank.createAccount("B", TestFixtures.HKD, Money.zero(TestFixtures.HKD));

        Transaction tx = bank.transfer(a.getId(), b.getId(), Money.ofMinor(30_00, TestFixtures.HKD));

        assertThat(tx.getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(tx.getEntries()).hasSize(2); // no contra for same currency
        assertThat(bank.getBalance(a.getId())).isEqualTo(Money.ofMinor(70_00, TestFixtures.HKD));
        assertThat(bank.getBalance(b.getId())).isEqualTo(Money.ofMinor(30_00, TestFixtures.HKD));
    }

    @Test
    void crossCurrencyTransferConvertsAtSpotRate() {
        Account hkd = bank.createAccount("HKD Co", TestFixtures.HKD, Money.ofMinor(100_000_00, TestFixtures.HKD)); // HKD 100,000.00
        Account usd = bank.createAccount("USD Co", TestFixtures.USD, Money.zero(TestFixtures.USD));

        // HKD 1,000.00 @ 0.128 = USD 128.00 exactly (no residue).
        Transaction tx = bank.transfer(hkd.getId(), usd.getId(), Money.ofMinor(1_000_00, TestFixtures.HKD));

        assertThat(tx.getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(tx.getEntries()).hasSize(4); // 2 entries per currency leg
        assertThat(tx.getEntries()).anyMatch(e -> e.getAccount().equals(ContraAccountIds.FX_CONTRA));
        assertThat(bank.getBalance(hkd.getId())).isEqualTo(Money.ofMinor(99_000_00, TestFixtures.HKD)); // HKD 99,000.00
        assertThat(bank.getBalance(usd.getId())).isEqualTo(Money.ofMinor(128_00, TestFixtures.USD)); // USD 128.00
    }

    @Test
    void crossCurrencyTransferBooksRoundingResidueToFxContra() {
        Account hkd = bank.createAccount("HKD Co", TestFixtures.HKD, Money.ofMinor(1000_00, TestFixtures.HKD));
        Account usd = bank.createAccount("USD Co", TestFixtures.USD, Money.zero(TestFixtures.USD));

        // HKD 10.55 @ 0.128 = 1.3504 USD -> HALF_UP -> USD 1.35 (135 cents). Residue absorbed by FX contra.
        Transaction tx = bank.transfer(hkd.getId(), usd.getId(), Money.ofMinor(10_55, TestFixtures.HKD));

        assertThat(tx.getEntries()).hasSize(4);
        assertThat(bank.getBalance(usd.getId())).isEqualTo(Money.ofMinor(1_35, TestFixtures.USD)); // USD 1.35
        assertThat(bank.getBalance(hkd.getId())).isEqualTo(Money.ofMinor(989_45, TestFixtures.HKD)); // HKD 989.45
    }

    @Test
    void rejectsSelfTransfer() {
        Account a = bank.createAccount("A", TestFixtures.HKD, Money.ofMinor(100_00, TestFixtures.HKD));
        assertThatThrownBy(() -> bank.transfer(a.getId(), a.getId(), Money.ofMinor(10_00, TestFixtures.HKD)))
                .isInstanceOf(SameAccountTransferException.class);
    }

    @Test
    void insufficientFundsLeavesBothBalancesUnchanged() {
        Account a = bank.createAccount("A", TestFixtures.HKD, Money.ofMinor(50_00, TestFixtures.HKD));
        Account b = bank.createAccount("B", TestFixtures.HKD, Money.zero(TestFixtures.HKD));

        assertThatThrownBy(() -> bank.transfer(a.getId(), b.getId(), Money.ofMinor(100_00, TestFixtures.HKD)))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(bank.getBalance(a.getId())).isEqualTo(Money.ofMinor(50_00, TestFixtures.HKD));
        assertThat(bank.getBalance(b.getId())).isEqualTo(Money.zero(TestFixtures.HKD));
    }

    @Test
    void rejectsTransferToUnknownAccount() {
        Account a = bank.createAccount("A", TestFixtures.HKD, Money.ofMinor(100_00, TestFixtures.HKD));
        assertThatThrownBy(() -> bank.transfer(a.getId(), AccountId.random(), Money.ofMinor(10_00, TestFixtures.HKD)))
                .isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> bank.transfer(AccountId.random(), a.getId(), Money.ofMinor(10_00, TestFixtures.HKD)))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void transferAmountMustBeInSourceCurrency() {
        Account hkd = bank.createAccount("HKD Co", TestFixtures.HKD, Money.ofMinor(1000_00, TestFixtures.HKD));
        Account usd = bank.createAccount("USD Co", TestFixtures.USD, Money.zero(TestFixtures.USD));

        // Amount expressed in USD but source is HKD -> rejected.
        assertThatThrownBy(() -> bank.transfer(hkd.getId(), usd.getId(), Money.ofMinor(10_00, TestFixtures.USD)))
                .isInstanceOf(CurrencyMismatchException.class);
    }
}
