package com.firstcircle.banking;

import com.firstcircle.banking.concurrency.LockManager;
import com.firstcircle.banking.fx.ExchangeRateProvider;
import com.firstcircle.banking.fx.InMemoryExchangeRateProvider;
import com.firstcircle.banking.idempotency.InMemoryIdempotencyStore;
import com.firstcircle.banking.repo.InMemoryAccountRepository;
import com.firstcircle.banking.repo.InMemoryLedgerRepository;
import com.firstcircle.banking.repo.LedgerRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;

/** Shared constants and wiring helpers for tests. */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static final Currency HKD = Currency.getInstance("HKD");
    public static final Currency USD = Currency.getInstance("USD");
    public static final Currency JPY = Currency.getInstance("JPY"); // zero-decimal currency
    public static final Currency EUR = Currency.getInstance("EUR");

    /** Fixed clock so transaction timestamps are deterministic. */
    public static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC);

    public static ExchangeRateProvider defaultFx() {
        return InMemoryExchangeRateProvider.builder()
                .rate("HKD", "USD", "0.128")
                .rate("USD", "HKD", "7.8")
                .rate("USD", "JPY", "150")
                .rate("JPY", "USD", "0.0067")
                .build();
    }

    /** A fresh, fully-isolated service with the default FX table and no persisted state. */
    public static BankingService newService() {
        return new BankingService(
                new InMemoryAccountRepository(),
                new InMemoryLedgerRepository(),
                defaultFx(),
                new LockManager(),
                new InMemoryIdempotencyStore(),
                FIXED_CLOCK);
    }

    /** A service plus its ledger repository, for tests that inspect the ledger. */
    public static ServiceWiring newServiceWithWiring() {
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        LedgerRepository ledger = new InMemoryLedgerRepository();
        BankingService service = new BankingService(
                accounts, ledger, defaultFx(), new LockManager(), new InMemoryIdempotencyStore(), FIXED_CLOCK);
        return new ServiceWiring(service, accounts, ledger);
    }

    public record ServiceWiring(BankingService service, InMemoryAccountRepository accounts, LedgerRepository ledger) {
    }
}
