package com.firstcircle.banking.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstcircle.banking.BankingService;
import com.firstcircle.banking.TestFixtures;
import com.firstcircle.banking.domain.Account;
import com.firstcircle.banking.domain.Money;
import com.firstcircle.banking.domain.Transaction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class IdempotencyTest {

    private BankingService bank;

    @BeforeEach
    void setUp() {
        bank = TestFixtures.newService();
    }

    @Test
    void replayReturnsSameTransactionWithoutDoubleApplying() {
        Account a = bank.createAccount("A", TestFixtures.HKD, Money.ofMinor(100_00, TestFixtures.HKD));
        IdempotencyKey key = IdempotencyKey.of("dep-1");

        Transaction first = bank.deposit(a.getId(), Money.ofMinor(50_00, TestFixtures.HKD), key);
        Transaction replay = bank.deposit(a.getId(), Money.ofMinor(50_00, TestFixtures.HKD), key);

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(bank.getBalance(a.getId())).isEqualTo(Money.ofMinor(150_00, TestFixtures.HKD)); // credited once
    }

    @Test
    void differentKeysExecuteIndependently() {
        Account a = bank.createAccount("A", TestFixtures.HKD, Money.ofMinor(100_00, TestFixtures.HKD));
        bank.deposit(a.getId(), Money.ofMinor(50_00, TestFixtures.HKD), IdempotencyKey.of("k1"));
        bank.deposit(a.getId(), Money.ofMinor(50_00, TestFixtures.HKD), IdempotencyKey.of("k2"));
        assertThat(bank.getBalance(a.getId())).isEqualTo(Money.ofMinor(200_00, TestFixtures.HKD)); // both applied
    }

    @Test
    void sameKeyWithDifferentParametersReturnsOriginal() {
        // Conflict detection is intentionally not provided: a reused key simply returns the original
        // result, regardless of the parameters in the second call.
        Account a = bank.createAccount("A", TestFixtures.HKD, Money.ofMinor(100_00, TestFixtures.HKD));
        IdempotencyKey key = IdempotencyKey.of("k");

        Transaction first = bank.deposit(a.getId(), Money.ofMinor(50_00, TestFixtures.HKD), key);
        Transaction second = bank.deposit(a.getId(), Money.ofMinor(99_00, TestFixtures.HKD), key);

        assertThat(second.getId()).isEqualTo(first.getId());                       // returns the original
        assertThat(bank.getBalance(a.getId())).isEqualTo(Money.ofMinor(150_00, TestFixtures.HKD)); // 50 credited once
    }

    @Test
    @Timeout(30)
    void concurrentSameKeyExecutesExactlyOnce() throws Exception {
        Account a = bank.createAccount("A", TestFixtures.HKD, Money.ofMinor(100_00, TestFixtures.HKD));
        IdempotencyKey key = IdempotencyKey.of("concurrent-deposit");
        int threads = 20;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Transaction>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(exec.submit(() -> {
                start.await();
                return bank.deposit(a.getId(), Money.ofMinor(10_00, TestFixtures.HKD), key);
            }));
        }
        start.countDown();
        Set<java.util.UUID> txIds = new HashSet<>();
        for (Future<Transaction> f : futures) {
            txIds.add(f.get().getId().getValue());
        }
        exec.shutdown();
        assertThat(exec.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(txIds).hasSize(1); // a single transaction was executed
        assertThat(bank.getBalance(a.getId())).isEqualTo(Money.ofMinor(110_00, TestFixtures.HKD)); // credited once
    }

    @Test
    void noKeyAlwaysExecutes() {
        // Operations without a key are never deduped: both deposits apply.
        Account a = bank.createAccount("A", TestFixtures.HKD, Money.ofMinor(100_00, TestFixtures.HKD));
        bank.deposit(a.getId(), Money.ofMinor(50_00, TestFixtures.HKD)); // no key
        bank.deposit(a.getId(), Money.ofMinor(50_00, TestFixtures.HKD)); // no key
        assertThat(bank.getBalance(a.getId())).isEqualTo(Money.ofMinor(200_00, TestFixtures.HKD)); // both applied
    }
}
