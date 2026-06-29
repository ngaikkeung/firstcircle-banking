package com.firstcircle.banking.demo;

import com.firstcircle.banking.BankingService;
import com.firstcircle.banking.DefaultBankingService;
import com.firstcircle.banking.db.DatabaseInitializer;
import com.firstcircle.banking.db.H2DataSources;
import com.firstcircle.banking.db.TransactionManager;
import com.firstcircle.banking.domain.Account;
import com.firstcircle.banking.domain.Money;
import com.firstcircle.banking.domain.Transaction;
import com.firstcircle.banking.exceptions.InsufficientFundsException;
import com.firstcircle.banking.fx.InMemoryExchangeRateProvider;
import com.firstcircle.banking.idempotency.IdempotencyKey;
import com.firstcircle.banking.repo.JdbcAccountRepository;
import com.firstcircle.banking.repo.JdbcLedgerRepository;

import java.time.Clock;
import java.util.Currency;
import javax.sql.DataSource;

/**
 * A runnable walkthrough of every banking operation exposed by the {@link BankingService} contract.
 *
 * <p>Covers: account creation (with and without initial deposit), deposit, withdrawal
 * (including overdraft rejection), same-currency transfer, balance enquiry, and idempotent replay.
 *
 * <p>Run with:
 * {@code mvn clean compile exec:java -Dexec.mainClass=com.firstcircle.banking.demo.Demo}
 */
public final class Demo {

    public static void main(String[] args) {
        // -- Wiring -----------------------------------------------------------
        DataSource dataSource = H2DataSources.inMemory("demo");
        DatabaseInitializer.init(dataSource);
        var tm = new TransactionManager(dataSource);

        BankingService bank = new DefaultBankingService(
                new JdbcAccountRepository(),
                new JdbcLedgerRepository(),
                InMemoryExchangeRateProvider.empty(),
                tm,
                Clock.systemUTC());

        Currency hkd = Currency.getInstance("HKD");

        // -- 1. Account creation -----------------------------------------------
        section("1. Account creation");

        Account alice = bank.createAccount("Alice", hkd, Money.ofMinor(100_000_00L, hkd));
        log("Created account '%s' with initial deposit %s", alice.getOwnerName(), alice.balance());

        Account bob = bank.createAccount("Bob", hkd, Money.zero(hkd));
        log("Created account '%s' with zero deposit (balance = %s)", bob.getOwnerName(), bob.balance());

        // -- 2. Balance enquiry ------------------------------------------------
        section("2. Balance enquiry");

        log("Alice balance: %s", bank.getBalance(alice.getId()));
        log("Bob   balance: %s", bank.getBalance(bob.getId()));

        // -- 3. Deposit --------------------------------------------------------
        section("3. Deposit");

        Transaction dep = bank.deposit(bob.getId(), Money.ofMinor(50_000_00L, hkd));
        log("Deposited HKD 50,000.00 to Bob (tx %s, type %s)", dep.getId().getValue(), dep.getType());
        log("Bob   balance: %s", bank.getBalance(bob.getId()));

        // -- 4. Withdrawal -----------------------------------------------------
        section("4. Withdrawal");

        Transaction wd = bank.withdraw(alice.getId(), Money.ofMinor(20_000_00L, hkd));
        log("Withdrew HKD 20,000.00 from Alice (tx %s, type %s)", wd.getId().getValue(), wd.getType());
        log("Alice balance: %s", bank.getBalance(alice.getId()));

        // Overdraft is rejected — balance stays unchanged.
        section("4a. Overdraft rejection");
        Money aliceBefore = bank.getBalance(alice.getId());
        try {
            bank.withdraw(alice.getId(), Money.ofMinor(999_000_00L, hkd));
        } catch (InsufficientFundsException e) {
            log("Overdraft rejected as expected: %s", e.getMessage());
        }
        Money aliceAfter = bank.getBalance(alice.getId());
        log("Alice balance unchanged: %s == %s → %s", aliceBefore, aliceAfter, aliceBefore.equals(aliceAfter));

        // -- 5. Transfer -------------------------------------------------------
        section("5. Transfer");

        Transaction tx = bank.transfer(alice.getId(), bob.getId(), Money.ofMinor(30_000_00L, hkd));
        log("Transferred HKD 30,000.00 from Alice → Bob (tx %s, type %s)", tx.getId().getValue(), tx.getType());
        log("Alice balance: %s", bank.getBalance(alice.getId()));
        log("Bob   balance: %s", bank.getBalance(bob.getId()));

        // -- 6. Idempotent deposit ---------------------------------------------
        section("6. Idempotent deposit (at-most-once)");

        IdempotencyKey key = IdempotencyKey.of("bonus-alice-001");
        Transaction first = bank.deposit(alice.getId(), Money.ofMinor(5_000_00L, hkd), key);
        log("First  deposit (key=bonus-alice-001): tx %s, Alice balance: %s",
                first.getId().getValue(), bank.getBalance(alice.getId()));

        Transaction replay = bank.deposit(alice.getId(), Money.ofMinor(5_000_00L, hkd), key);
        log("Replay deposit (same key):            tx %s, Alice balance: %s",
                replay.getId().getValue(), bank.getBalance(alice.getId()));

        log("Same transaction id? %s  (replay did NOT double-credit)", first.getId().equals(replay.getId()));

        // -- 7. Idempotent transfer --------------------------------------------
        section("7. Idempotent transfer (at-most-once)");

        IdempotencyKey txKey = IdempotencyKey.of("settle-bob-001");
        Transaction firstTx = bank.transfer(bob.getId(), alice.getId(), Money.ofMinor(10_000_00L, hkd), txKey);
        log("First  transfer (key=settle-bob-001): tx %s", firstTx.getId().getValue());

        Transaction replayTx = bank.transfer(bob.getId(), alice.getId(), Money.ofMinor(10_000_00L, hkd), txKey);
        log("Replay transfer (same key):           tx %s", replayTx.getId().getValue());
        log("Same transaction id? %s  (replay did NOT double-transfer)", firstTx.getId().equals(replayTx.getId()));

        // -- Final balances ----------------------------------------------------
        section("Final balances");
        log("Alice: %s", bank.getBalance(alice.getId()));
        log("Bob:   %s", bank.getBalance(bob.getId()));

        // -- Ledger summary ----------------------------------------------------
        section("Ledger (" + bank.ledger().size() + " transactions)");
        for (Transaction t : bank.ledger()) {
            log("  #%d  %-10s  %s  (%d entries)",
                    t.getSequence(), t.getType(), t.getTimestamp(), t.getEntries().size());
        }
    }

    // -- helpers --------------------------------------------------------------

    private static void section(String title) {
        System.out.println();
        System.out.println("--- " + title + " ---");
    }

    private static void log(String format, Object... args) {
        System.out.printf("  " + format + "%n", args);
    }
}
