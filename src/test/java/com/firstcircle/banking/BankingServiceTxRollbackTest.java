package com.firstcircle.banking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstcircle.banking.domain.Money;
import com.firstcircle.banking.exceptions.InsufficientFundsException;
import org.junit.jupiter.api.Test;

/**
 * Proves that a failed mutation rolls back atomically: neither the balance change nor the ledger
 * posting is left behind. This is the guarantee the DB transaction provides.
 */
class BankingServiceTxRollbackTest {

    @Test
    void failedWithdrawalLeavesBalanceAndLedgerUnchanged() {
        TestFixtures.ServiceWiring wiring = TestFixtures.newServiceWithWiring();
        BankingService bank = wiring.service();
        var a = bank.createAccount("A", TestFixtures.HKD, Money.ofMinor(100_00, TestFixtures.HKD));
        long txCountBefore = bank.ledger().size();

        // HKD 1,000.00 requested against an HKD 100.00 balance -> must fail, atomically.
        assertThatThrownBy(() -> bank.withdraw(a.getId(), Money.ofMinor(1000_00, TestFixtures.HKD)))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(bank.getBalance(a.getId())).isEqualTo(Money.ofMinor(100_00, TestFixtures.HKD)); // unchanged
        assertThat(bank.ledger().size()).isEqualTo(txCountBefore); // no ledger row written
    }

    @Test
    void failedTransferRollsBackBothAccounts() {
        TestFixtures.ServiceWiring wiring = TestFixtures.newServiceWithWiring();
        BankingService bank = wiring.service();
        var a = bank.createAccount("A", TestFixtures.HKD, Money.ofMinor(100_00, TestFixtures.HKD));
        var b = bank.createAccount("B", TestFixtures.HKD, Money.ofMinor(100_00, TestFixtures.HKD));
        long txCountBefore = bank.ledger().size();

        assertThatThrownBy(() -> bank.transfer(a.getId(), b.getId(), Money.ofMinor(999_00, TestFixtures.HKD)))
                .isInstanceOf(InsufficientFundsException.class);

        // Neither account moved, and nothing was posted.
        assertThat(bank.getBalance(a.getId())).isEqualTo(Money.ofMinor(100_00, TestFixtures.HKD));
        assertThat(bank.getBalance(b.getId())).isEqualTo(Money.ofMinor(100_00, TestFixtures.HKD));
        assertThat(bank.ledger().size()).isEqualTo(txCountBefore);
    }
}
