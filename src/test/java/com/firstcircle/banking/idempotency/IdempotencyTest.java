package com.firstcircle.banking.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstcircle.banking.BankingService;
import com.firstcircle.banking.TestFixtures;
import com.firstcircle.banking.domain.Account;
import com.firstcircle.banking.domain.Money;
import com.firstcircle.banking.domain.Transaction;
import com.firstcircle.banking.exceptions.IdempotencyConflictException;
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
    private TestFixtures.ServiceWiring wiring;

    @BeforeEach
    void setUp() {
        wiring = TestFixtures.newServiceWithWiring();
        bank = wiring.service();
    }

    @Test
    void replayReturnsSameTransactionWithoutDoubleApplying() {
        Account a = bank.createAccount("A", TestFixtures.HKD, Money.ofMinor(100_00, TestFixtures.HKD));
        IdempotencyKey key = IdempotencyKey.of("dep-1");

        Transaction first = bank.deposit(a.id(), Money.ofMinor(50_00, TestFixtures.HKD), key);
        Transaction replay = bank.deposit(a.id(), Money.ofMinor(50_00, TestFixtures.HKD), key);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(bank.getBalance(a.id())).isEqualTo(Money.ofMinor(150_00, TestFixtures.HKD)); // credited once
    }

    @Test
    void differentKeysExecuteIndependently() {
        Account a = bank.createAccount("A", TestFixtures.HKD, Money.ofMinor(100_00, TestFixtures.HKD));
        bank.deposit(a.id(), Money.ofMinor(50_00, TestFixtures.HKD), IdempotencyKey.of("k1"));
        bank.deposit(a.id(), Money.ofMinor(50_00, TestFixtures.HKD), IdempotencyKey.of("k2"));
        assertThat(bank.getBalance(a.id())).isEqualTo(Money.ofMinor(200_00, TestFixtures.HKD)); // both applied
    }

    @Test
    void sameKeyWithDifferentParametersConflicts() {
        Account a = bank.createAccount("A", TestFixtures.HKD, Money.ofMinor(100_00, TestFixtures.HKD));
        bank.deposit(a.id(), Money.ofMinor(50_00, TestFixtures.HKD), IdempotencyKey.of("k"));
        assertThatThrownBy(() -> bank.deposit(a.id(), Money.ofMinor(99_00, TestFixtures.HKD), IdempotencyKey.of("k")))
                .isInstanceOf(IdempotencyConflictException.class);
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
                return bank.deposit(a.id(), Money.ofMinor(10_00, TestFixtures.HKD), key);
            }));
        }
        start.countDown();
        Set<java.util.UUID> txIds = new HashSet<>();
        for (Future<Transaction> f : futures) {
            txIds.add(f.get().id().value());
        }
        exec.shutdown();
        assertThat(exec.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(txIds).hasSize(1); // a single transaction was executed
        assertThat(bank.getBalance(a.id())).isEqualTo(Money.ofMinor(110_00, TestFixtures.HKD)); // credited once
    }

    @Test
    void noKeyLeavesNoIdempotencyRow() {
        Account a = bank.createAccount("A", TestFixtures.HKD, Money.ofMinor(100_00, TestFixtures.HKD));
        bank.deposit(a.id(), Money.ofMinor(50_00, TestFixtures.HKD)); // no key
        bank.deposit(a.id(), Money.ofMinor(50_00, TestFixtures.HKD)); // no key
        assertThat(bank.getBalance(a.id())).isEqualTo(Money.ofMinor(200_00, TestFixtures.HKD)); // both applied

        // No idempotency claim was recorded because no key was supplied.
        JdbcIdempotencyRepository idem = new JdbcIdempotencyRepository();
        long rows = wiring.tm().run(idem::count);
        assertThat(rows).isZero();
    }
}
