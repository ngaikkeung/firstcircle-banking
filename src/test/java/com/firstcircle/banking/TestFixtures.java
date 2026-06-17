package com.firstcircle.banking;

import com.firstcircle.banking.db.DatabaseInitializer;
import com.firstcircle.banking.db.H2DataSources;
import com.firstcircle.banking.db.TransactionManager;
import com.firstcircle.banking.fx.ExchangeRateProvider;
import com.firstcircle.banking.fx.InMemoryExchangeRateProvider;
import com.firstcircle.banking.repo.JdbcAccountRepository;
import com.firstcircle.banking.repo.JdbcLedgerRepository;
import com.firstcircle.banking.repo.LedgerRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;
import java.util.function.BiFunction;
import javax.sql.DataSource;

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

    /** A fresh, fully-isolated service (own in-memory H2 database) with the default FX table. */
    public static BankingService newService() {
        return newServiceWithWiring().service();
    }

    /** A service plus its JDBC pieces, for tests that inspect storage directly. */
    public static ServiceWiring newServiceWithWiring() {
        DataSource dataSource = H2DataSources.inMemory("test-" + UUID.randomUUID());
        DatabaseInitializer.init(dataSource);
        TransactionManager tm = new TransactionManager(dataSource);
        JdbcLedgerRepository ledger = new JdbcLedgerRepository();
        BankingService service = new BankingService(
                new JdbcAccountRepository(),
                ledger,
                defaultFx(),
                tm,
                FIXED_CLOCK);
        return new ServiceWiring(service, dataSource, tm, ledger);
    }

    /**
     * The service plus its H2 pieces. {@code tm} lets tests run read-only queries against the same
     * connection pool (repository read methods take a {@link java.sql.Connection}).
     */
    public record ServiceWiring(
            BankingService service,
            DataSource dataSource,
            TransactionManager tm,
            LedgerRepository ledger) {

        /** Run a read against the ledger within a transaction (the reader gets the connection). */
        public <T> T readLedger(BiFunction<LedgerRepository, java.sql.Connection, T> reader) {
            return tm.run(conn -> reader.apply(ledger, conn));
        }
    }
}
