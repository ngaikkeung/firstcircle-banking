package com.firstcircle.banking.demo;

import com.firstcircle.banking.BankingService;
import com.firstcircle.banking.concurrency.LockManager;
import com.firstcircle.banking.domain.Account;
import com.firstcircle.banking.domain.Money;
import com.firstcircle.banking.domain.Transaction;
import com.firstcircle.banking.fx.InMemoryExchangeRateProvider;
import com.firstcircle.banking.idempotency.IdempotencyKey;
import com.firstcircle.banking.idempotency.InMemoryIdempotencyStore;
import com.firstcircle.banking.repo.InMemoryAccountRepository;
import com.firstcircle.banking.repo.InMemoryLedgerRepository;
import com.firstcircle.banking.repo.LedgerRepository;
import java.time.Clock;
import java.util.Currency;

/**
 * A small runnable example that wires the in-memory adapters together and exercises the full
 * lifecycle: multi-currency account creation, deposit, cross-currency transfer, withdrawal, and
 * an idempotent deposit replay.
 *
 * <p>Run with: {@code mvn -q exec:java -Dexec.mainClass=com.firstcircle.banking.demo.Demo}
 * (requires the exec-maven-plugin) or by running the class from your IDE.
 */
public final class Demo {

    public static void main(String[] args) {
        var fx = InMemoryExchangeRateProvider.builder()
                .rate("HKD", "USD", "0.128")
                .rate("USD", "HKD", "7.8")
                .build();
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        LedgerRepository ledger = new InMemoryLedgerRepository();
        var bank = new BankingService(
                accounts,
                ledger,
                fx,
                new LockManager(),
                new InMemoryIdempotencyStore(),
                Clock.systemUTC());

        Currency hkd = Currency.getInstance("HKD");
        Currency usd = Currency.getInstance("USD");

        Account acme = bank.createAccount("Acme Trading", hkd, Money.ofMinor(100_000_00L, hkd)); // HKD 100,000.00
        Account beta = bank.createAccount("Beta Corp", usd, Money.zero(usd));
        bank.deposit(beta.id(), Money.ofMinor(5_000_00L, usd)); // USD 5,000.00

        println("--- Balances before transfer ---");
        println("  Acme (HKD): " + bank.getBalance(acme.id()));
        println("  Beta (USD): " + bank.getBalance(beta.id()));

        bank.transfer(acme.id(), beta.id(), Money.ofMinor(10_000_00L, hkd)); // HKD 10,000 -> USD
        bank.withdraw(acme.id(), Money.ofMinor(1_500_00L, hkd)); // HKD 1,500.00

        println("--- Balances after cross-currency transfer + withdrawal ---");
        println("  Acme (HKD): " + bank.getBalance(acme.id()));
        println("  Beta (USD): " + bank.getBalance(beta.id()));

        // Idempotency: the same key replays the exact same transaction instead of depositing twice.
        IdempotencyKey key = IdempotencyKey.of("deposit-acme-001");
        Transaction first = bank.deposit(acme.id(), Money.ofMinor(2_000_00L, hkd), key);
        Transaction replay = bank.deposit(acme.id(), Money.ofMinor(2_000_00L, hkd), key);
        println("Idempotent deposit replay returned same transaction id: " + first.id().equals(replay.id()));
        println("  Acme (HKD): " + bank.getBalance(acme.id()));

        println("--- Ledger (" + ledger.findAll().size() + " transactions) ---");
        for (Transaction tx : ledger.findAll()) {
            println("  " + tx);
        }
    }

    private static void println(Object line) {
        System.out.println(line);
    }
}
