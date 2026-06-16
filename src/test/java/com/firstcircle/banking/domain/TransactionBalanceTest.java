package com.firstcircle.banking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstcircle.banking.TestFixtures;
import com.firstcircle.banking.ledger.ContraAccountIds;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransactionBalanceTest {

    private static final Instant NOW = Instant.parse("2025-01-01T00:00:00Z");

    @Test
    void sameCurrencyCreditAndDebitBalances() {
        AccountId alice = AccountId.random();
        long amount = 5000;

        Transaction tx = Transaction.create(
                TransactionId.random(), 1L, NOW, TransactionType.TRANSFER,
                List.of(
                        LedgerEntry.debit(alice, TestFixtures.HKD, amount),
                        LedgerEntry.credit(AccountId.random(), TestFixtures.HKD, amount)));

        assertThat(tx.entries()).hasSize(2);
        assertThat(tx.entriesFor(alice)).hasSize(1);
    }

    @Test
    void crossCurrencyLegsEachBalance() {
        // HKD leg and USD leg each net to zero; the FX contra appears in both.
        long phpAmount = 100000;
        long usdAmount = 1750;
        Transaction tx = Transaction.create(
                TransactionId.random(), 1L, NOW, TransactionType.TRANSFER,
                List.of(
                        LedgerEntry.debit(AccountId.random(), TestFixtures.HKD, phpAmount),
                        LedgerEntry.credit(ContraAccountIds.FX_CONTRA, TestFixtures.HKD, phpAmount),
                        LedgerEntry.debit(ContraAccountIds.FX_CONTRA, TestFixtures.USD, usdAmount),
                        LedgerEntry.credit(AccountId.random(), TestFixtures.USD, usdAmount)));

        assertThat(tx.entries()).hasSize(4);
    }

    @Test
    void unbalancedSameCurrencyIsRejected() {
        assertThatThrownBy(() -> Transaction.create(
                TransactionId.random(), 1L, NOW, TransactionType.TRANSFER,
                List.of(
                        LedgerEntry.debit(AccountId.random(), TestFixtures.HKD, 5000),
                        LedgerEntry.credit(AccountId.random(), TestFixtures.HKD, 4999))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unbalancedInOneLegOfCrossCurrencyIsRejected() {
        // USD leg does not balance -> the whole transaction is rejected.
        assertThatThrownBy(() -> Transaction.create(
                TransactionId.random(), 1L, NOW, TransactionType.TRANSFER,
                List.of(
                        LedgerEntry.debit(AccountId.random(), TestFixtures.HKD, 100000),
                        LedgerEntry.credit(ContraAccountIds.FX_CONTRA, TestFixtures.HKD, 100000),
                        LedgerEntry.debit(ContraAccountIds.FX_CONTRA, TestFixtures.USD, 1750),
                        LedgerEntry.credit(AccountId.random(), TestFixtures.USD, 1700))))
                .isInstanceOf(IllegalStateException.class);
    }
}
