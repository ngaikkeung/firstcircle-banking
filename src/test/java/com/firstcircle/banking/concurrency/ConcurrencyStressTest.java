package com.firstcircle.banking.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstcircle.banking.BankingService;
import com.firstcircle.banking.TestFixtures;
import com.firstcircle.banking.domain.Account;
import com.firstcircle.banking.domain.LedgerEntry;
import com.firstcircle.banking.domain.Money;
import com.firstcircle.banking.domain.Transaction;
import com.firstcircle.banking.exceptions.InsufficientFundsException;
import com.firstcircle.banking.exceptions.SameAccountTransferException;
import com.firstcircle.banking.idempotency.InMemoryIdempotencyStore;
import com.firstcircle.banking.repo.InMemoryAccountRepository;
import com.firstcircle.banking.repo.InMemoryLedgerRepository;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Proves the service is correct under contention: no overdrafts, no lost updates, no lost money,
 * no deadlocks, and the ledger is never observed half-written. All tests use a start-gate
 * latch so threads truly run concurrently, and a {@link Timeout} so a deadlock would fail the
 * build rather than hang it.
 */
class ConcurrencyStressTest {

    private static final Currency HKD = TestFixtures.HKD;

    private BankingService bank;
    private InMemoryLedgerRepository ledger;

    @BeforeEach
    void setUp() {
        ledger = new InMemoryLedgerRepository();
        bank = new BankingService(
                new InMemoryAccountRepository(),
                ledger,
                TestFixtures.defaultFx(),
                new LockManager(),
                new InMemoryIdempotencyStore(),
                TestFixtures.FIXED_CLOCK);
    }

    /** HKD 100.00 funded; 50 threads each try to withdraw HKD 10.00 -> exactly 10 win, balance hits 0. */
    @Test
    @Timeout(30)
    void overdraftRaceExhaustsFundsExactly() throws Exception {
        Account a = bank.createAccount("A", HKD, Money.ofMinor(100_00, HKD));
        int threads = 50;
        Money each = Money.ofMinor(10_00, HKD);

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            futures.add(exec.submit(() -> {
                start.await();
                try {
                    bank.withdraw(a.id(), each);
                    successes.incrementAndGet();
                } catch (InsufficientFundsException e) {
                    insufficient.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        waitForAll(futures);
        exec.shutdown();

        assertThat(successes.get()).isEqualTo(10);
        assertThat(insufficient.get()).isEqualTo(40);
        assertThat(bank.getBalance(a.id())).isEqualTo(Money.zero(HKD));
        assertLedgerBalanced();
    }

    /** 100 concurrent deposits of HKD 1.00 must all be counted (no lost updates). */
    @Test
    @Timeout(30)
    void concurrentDepositsHaveNoLostUpdates() throws Exception {
        Account a = bank.createAccount("A", HKD, Money.ofMinor(1000_00, HKD)); // start HKD 1,000.00
        int threads = 100;
        Money each = Money.ofMinor(1_00, HKD);

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(exec.submit(() -> {
                start.await();
                bank.deposit(a.id(), each);
                return null;
            }));
        }
        start.countDown();
        waitForAll(futures);
        exec.shutdown();

        // HKD 1,000.00 + 100 * HKD 1.00 == HKD 1,100.00 exactly.
        assertThat(bank.getBalance(a.id())).isEqualTo(Money.ofMinor(1100_00, HKD));
        assertLedgerBalanced();
    }

    /** Many random same-currency transfers across accounts: total money never changes. */
    @Test
    @Timeout(60)
    void closedSystemConservesMoney() throws Exception {
        int n = 10;
        List<Account> accountsList = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < n; i++) {
            long initial = (long) (i + 1) * 100_00; // HKD 100.00, 200.00, ...
            accountsList.add(bank.createAccount("A" + i, HKD, Money.ofMinor(initial, HKD)));
            total += initial;
        }
        long initialTotal = total;

        int threads = 16;
        int opsPerThread = 2000;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(exec.submit(() -> {
                start.await();
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                for (int k = 0; k < opsPerThread; k++) {
                    Account from = accountsList.get(rng.nextInt(n));
                    Account to = accountsList.get(rng.nextInt(n));
                    long amount = rng.nextLong(1, 50_00); // HKD 0.01 .. 49.99
                    try {
                        bank.transfer(from.id(), to.id(), Money.ofMinor(amount, HKD));
                    } catch (InsufficientFundsException | SameAccountTransferException ignored) {
                        // failures don't move money; conservation still holds
                    }
                }
                return null;
            }));
        }
        start.countDown();
        waitForAll(futures);
        exec.shutdown();

        long finalTotal = 0;
        for (Account acc : accountsList) {
            finalTotal += bank.getBalance(acc.id()).minor();
        }
        assertThat(finalTotal).isEqualTo(initialTotal);
        assertLedgerBalanced();
    }

    /** Half the threads transfer A->B, half B->A, concurrently -> exercises lock ordering; must not deadlock. */
    @Test
    @Timeout(30)
    void pingPongBetweenTwoAccountsDoesNotDeadlock() throws Exception {
        Account a = bank.createAccount("A", HKD, Money.ofMinor(500_00, HKD));
        Account b = bank.createAccount("B", HKD, Money.ofMinor(500_00, HKD));
        long initialTotal = 1000_00;

        int threads = 8;
        int rounds = 5000;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final boolean aToB = (t % 2 == 0);
            futures.add(exec.submit(() -> {
                start.await();
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                for (int k = 0; k < rounds; k++) {
                    Account from = aToB ? a : b;
                    Account to = aToB ? b : a;
                    long amount = rng.nextLong(1, 20_00);
                    try {
                        bank.transfer(from.id(), to.id(), Money.ofMinor(amount, HKD));
                    } catch (InsufficientFundsException ignored) {
                        // fine
                    }
                }
                return null;
            }));
        }
        start.countDown();
        waitForAll(futures); // would hang until @Timeout if deadlocked
        exec.shutdown();

        long finalTotal = bank.getBalance(a.id()).minor() + bank.getBalance(b.id()).minor();
        assertThat(finalTotal).isEqualTo(initialTotal);
        assertLedgerBalanced();
    }

    // ----------------------------------------------------------- helpers

    private static void waitForAll(List<Future<?>> futures) throws Exception {
        for (Future<?> f : futures) {
            f.get();
        }
    }

    /** Every transaction in the ledger must still net to zero per currency (no partial writes). */
    private void assertLedgerBalanced() {
        for (Transaction tx : ledger.findAll()) {
            Map<Currency, Long> sums = new HashMap<>();
            for (LedgerEntry e : tx.entries()) {
                sums.merge(e.currency(), e.signedAmount(), Long::sum);
            }
            for (Map.Entry<Currency, Long> entry : sums.entrySet()) {
                assertThat(entry.getValue())
                        .as("transaction #%s is unbalanced in %s", tx.sequence(), entry.getKey())
                        .isZero();
            }
        }
    }
}
