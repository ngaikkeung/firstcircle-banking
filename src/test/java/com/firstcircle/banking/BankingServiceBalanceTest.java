package com.firstcircle.banking;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstcircle.banking.domain.Account;
import com.firstcircle.banking.domain.LedgerEntry;
import com.firstcircle.banking.domain.Money;
import org.junit.jupiter.api.Test;

class BankingServiceBalanceTest {

    @Test
    void balanceIsReturnedInAccountCurrency() {
        BankingService bank = TestFixtures.newService();
        Account usd = bank.createAccount("USD Co", TestFixtures.USD, Money.ofMinor(50_00, TestFixtures.USD));
        assertThat(bank.getBalance(usd.id()).currency()).isEqualTo(TestFixtures.USD);
    }

    @Test
    void balanceReflectsOperationsAndEqualsLedgerSum() {
        TestFixtures.ServiceWiring wiring = TestFixtures.newServiceWithWiring();
        BankingService bank = wiring.service();
        Account a = bank.createAccount("A", TestFixtures.HKD, Money.ofMinor(100_00, TestFixtures.HKD));
        bank.deposit(a.id(), Money.ofMinor(50_00, TestFixtures.HKD));
        bank.withdraw(a.id(), Money.ofMinor(30_00, TestFixtures.HKD));

        assertThat(bank.getBalance(a.id())).isEqualTo(Money.ofMinor(120_00, TestFixtures.HKD)); // 100 + 50 - 30

        long ledgerSum = wiring.readLedger((ledger, conn) -> ledger.entriesFor(a.id(), conn).stream()
                        .mapToLong(LedgerEntry::signedAmount)
                        .sum());
        assertThat(ledgerSum).isEqualTo(12_000L); // HKD 120.00 in minor units
    }
}
