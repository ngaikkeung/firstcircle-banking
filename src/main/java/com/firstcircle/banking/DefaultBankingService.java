package com.firstcircle.banking;

import com.firstcircle.banking.db.DataAccessException;
import com.firstcircle.banking.db.TransactionManager;
import com.firstcircle.banking.db.UniqueViolationException;
import com.firstcircle.banking.domain.Account;
import com.firstcircle.banking.domain.AccountId;
import com.firstcircle.banking.domain.LedgerEntry;
import com.firstcircle.banking.domain.Money;
import com.firstcircle.banking.domain.Transaction;
import com.firstcircle.banking.domain.TransactionId;
import com.firstcircle.banking.domain.TransactionType;
import com.firstcircle.banking.exceptions.AccountNotFoundException;
import com.firstcircle.banking.exceptions.CurrencyMismatchException;
import com.firstcircle.banking.exceptions.InsufficientFundsException;
import com.firstcircle.banking.exceptions.NegativeAmountException;
import com.firstcircle.banking.exceptions.SameAccountTransferException;
import com.firstcircle.banking.fx.ExchangeRateProvider;
import com.firstcircle.banking.idempotency.IdempotencyKey;
import com.firstcircle.banking.ledger.ContraAccountIds;
import com.firstcircle.banking.repo.AccountRepository;
import com.firstcircle.banking.repo.LedgerRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default implementation of {@link BankingService}. Coordinates account storage, the append-only
 * ledger, FX conversion, and transaction management to keep money correct and auditable.
 *
 * <p>This is the single place that enforces:
 * <ul>
 *   <li><b>Transactions</b> — every operation runs in one database transaction (via
 *       {@link TransactionManager}). Balance changes and the ledger posting commit together or not
 *       at all, so an operation can never be observed half-applied.</li>
 *   <li><b>Row locking</b> — accounts are locked with {@code SELECT ... FOR UPDATE} acquired in a
 *       single canonical {@link AccountId} order, so two transfers over the same pair can never
 *       deadlock, and a read-modify-write of a balance is never interleaved with another.</li>
 *   <li><b>Double-entry ledger</b> — every mutation posts a {@link Transaction} that is guaranteed
 *       to net to zero per currency.</li>
 *   <li><b>FX</b> — cross-currency transfers convert at a spot rate and book the rounding residue to
 *       an FX contra account.</li>
 *   <li><b>Idempotency</b> — a mutating operation may carry an {@link IdempotencyKey}, stored as a
 *       {@code UNIQUE} column on the entity it produced. A duplicate key returns the original
 *       result without re-executing; concurrent same-key races are resolved by the constraint.</li>
 * </ul>
 */
public final class DefaultBankingService implements BankingService {

    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final ExchangeRateProvider fx;
    private final TransactionManager tm;
    private final Clock clock;
    private final AtomicLong transactionSequence = new AtomicLong(0L);

    public DefaultBankingService(AccountRepository accounts, LedgerRepository ledger,
                                 ExchangeRateProvider fx, TransactionManager tm, Clock clock) {
        this.accounts = Objects.requireNonNull(accounts);
        this.ledger = Objects.requireNonNull(ledger);
        this.fx = Objects.requireNonNull(fx);
        this.tm = Objects.requireNonNull(tm);
        this.clock = Objects.requireNonNull(clock);
    }

    // ---------------------------------------------------------------- create

    @Override
    public Account createAccount(String ownerName, Currency currency, Money initialDeposit) {
        return createAccount(ownerName, currency, initialDeposit, null);
    }

    @Override
    public Account createAccount(String ownerName, Currency currency, Money initialDeposit, IdempotencyKey key) {
        Objects.requireNonNull(ownerName, "ownerName");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(initialDeposit, "initialDeposit");
        String rk = requestKey(key);
        try {
            return tm.run(conn -> doCreateAccount(conn, ownerName, currency, initialDeposit, rk));
        } catch (UniqueViolationException e) {
            if (rk == null) {
                throw e;
            }
            return loadAccountByKey(rk, e);
        }
    }

    private Account doCreateAccount(Connection conn, String ownerName, Currency currency,
                                    Money initialDeposit, String rk) {
        if (rk != null) {
            Optional<Account> existing = accounts.findByRequestKey(rk, conn);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        if (!initialDeposit.currency().equals(currency)) {
            throw new CurrencyMismatchException(currency, initialDeposit.currency());
        }
        Account account = new Account(AccountId.random(), ownerName, currency, initialDeposit.minor());
        accounts.insert(account, rk, conn);
        if (initialDeposit.isPositive()) {
            Transaction tx = record(TransactionId.random(), TransactionType.CREATE, List.of(
                    LedgerEntry.credit(account.id(), currency, initialDeposit.minor()),
                    LedgerEntry.debit(ContraAccountIds.CASH_CONTRA, currency, initialDeposit.minor())));
            ledger.append(tx, null, conn);
        }
        return account;
    }

    // ---------------------------------------------------------------- deposit

    @Override
    public Transaction deposit(AccountId id, Money amount) {
        return deposit(id, amount, null);
    }

    @Override
    public Transaction deposit(AccountId id, Money amount, IdempotencyKey key) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(amount, "amount");
        String rk = requestKey(key);
        try {
            return tm.run(conn -> doDeposit(conn, id, amount, rk));
        } catch (UniqueViolationException e) {
            return loadTransactionByKey(rk, e);
        }
    }

    private Transaction doDeposit(Connection conn, AccountId id, Money amount, String rk) {
        if (rk != null) {
            Optional<Transaction> existing = ledger.findByRequestKey(rk, conn);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        requirePositiveAmount(amount);
        Account account = accounts.findForUpdate(id, conn).orElseThrow(() -> new AccountNotFoundException(id));
        requireSameCurrency(amount.currency(), account.currency());
        account = account.credit(amount.minor());
        Transaction tx = record(TransactionId.random(), TransactionType.DEPOSIT, List.of(
                LedgerEntry.credit(account.id(), account.currency(), amount.minor()),
                LedgerEntry.debit(ContraAccountIds.CASH_CONTRA, account.currency(), amount.minor())));
        accounts.update(account, conn);
        ledger.append(tx, rk, conn);
        return tx;
    }

    // ---------------------------------------------------------------- withdraw

    @Override
    public Transaction withdraw(AccountId id, Money amount) {
        return withdraw(id, amount, null);
    }

    @Override
    public Transaction withdraw(AccountId id, Money amount, IdempotencyKey key) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(amount, "amount");
        String rk = requestKey(key);
        try {
            return tm.run(conn -> doWithdraw(conn, id, amount, rk));
        } catch (UniqueViolationException e) {
            return loadTransactionByKey(rk, e);
        }
    }

    private Transaction doWithdraw(Connection conn, AccountId id, Money amount, String rk) {
        if (rk != null) {
            Optional<Transaction> existing = ledger.findByRequestKey(rk, conn);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        requirePositiveAmount(amount);
        Account account = accounts.findForUpdate(id, conn).orElseThrow(() -> new AccountNotFoundException(id));
        requireSameCurrency(amount.currency(), account.currency());
        if (account.balanceMinor() < amount.minor()) {
            throw new InsufficientFundsException(account.id(), account.balanceMinor(), amount.minor());
        }
        account = account.debit(amount.minor());
        Transaction tx = record(TransactionId.random(), TransactionType.WITHDRAWAL, List.of(
                LedgerEntry.debit(account.id(), account.currency(), amount.minor()),
                LedgerEntry.credit(ContraAccountIds.CASH_CONTRA, account.currency(), amount.minor())));
        accounts.update(account, conn);
        ledger.append(tx, rk, conn);
        return tx;
    }

    // ---------------------------------------------------------------- transfer

    @Override
    public Transaction transfer(AccountId from, AccountId to, Money amount) {
        return transfer(from, to, amount, null);
    }

    @Override
    public Transaction transfer(AccountId from, AccountId to, Money amount, IdempotencyKey key) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(amount, "amount");
        String rk = requestKey(key);
        try {
            return tm.run(conn -> doTransfer(conn, from, to, amount, rk));
        } catch (UniqueViolationException e) {
            return loadTransactionByKey(rk, e);
        }
    }

    private Transaction doTransfer(Connection conn, AccountId from, AccountId to, Money amount, String rk) {
        requirePositiveAmount(amount);
        if (from.equals(to)) {
            throw new SameAccountTransferException();
        }
        if (rk != null) {
            Optional<Transaction> existing = ledger.findByRequestKey(rk, conn);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        // Lock both rows in canonical AccountId order -> two transfers over the same pair can never deadlock.
        List<AccountId> ordered = new ArrayList<>(new TreeSet<>(List.of(from, to)));
        Account first = accounts.findForUpdate(ordered.get(0), conn)
                .orElseThrow(() -> new AccountNotFoundException(ordered.get(0)));
        Account second = accounts.findForUpdate(ordered.get(1), conn)
                .orElseThrow(() -> new AccountNotFoundException(ordered.get(1)));
        Account source = first.id().equals(from) ? first : second;
        Account destination = first.id().equals(to) ? first : second;

        // The transfer amount is expressed in the source account's currency.
        requireSameCurrency(amount.currency(), source.currency());
        if (source.balanceMinor() < amount.minor()) {
            throw new InsufficientFundsException(source.id(), source.balanceMinor(), amount.minor());
        }

        long sourceMinor = amount.minor();
        if (source.currency().equals(destination.currency())) {
            source = source.debit(sourceMinor);
            destination = destination.credit(sourceMinor);
            Transaction tx = record(TransactionId.random(), TransactionType.TRANSFER, List.of(
                    LedgerEntry.debit(source.id(), source.currency(), sourceMinor),
                    LedgerEntry.credit(destination.id(), destination.currency(), sourceMinor)));
            accounts.update(source, conn);
            accounts.update(destination, conn);
            ledger.append(tx, rk, conn);
            return tx;
        }

        // Cross-currency: convert at the spot rate; the FX contra absorbs the position and any
        // rounding residue so each currency leg independently nets to zero.
        BigDecimal rate = fx.rate(source.currency(), destination.currency()); // throws if unavailable
        long destinationMinor = amount.convert(destination.currency(), rate).minor();

        source = source.debit(sourceMinor);
        destination = destination.credit(destinationMinor);
        Transaction tx = record(TransactionId.random(), TransactionType.TRANSFER, List.of(
                LedgerEntry.debit(source.id(), source.currency(), sourceMinor),
                LedgerEntry.credit(ContraAccountIds.FX_CONTRA, source.currency(), sourceMinor),
                LedgerEntry.debit(ContraAccountIds.FX_CONTRA, destination.currency(), destinationMinor),
                LedgerEntry.credit(destination.id(), destination.currency(), destinationMinor)));
        accounts.update(source, conn);
        accounts.update(destination, conn);
        ledger.append(tx, rk, conn);
        return tx;
    }

    // ---------------------------------------------------------------- balance

    @Override
    public Money getBalance(AccountId id) {
        Objects.requireNonNull(id, "id");
        return tm.run(conn -> accounts.findById(id, conn)
                .orElseThrow(() -> new AccountNotFoundException(id))
                .balance());
    }

    @Override
    public List<Transaction> ledger() {
        return tm.run(ledger::findAll);
    }

    // ---------------------------------------------------------------- helpers

    /** Unwrap the key, or null when no idempotency key was supplied. */
    private static String requestKey(IdempotencyKey key) {
        return key != null ? key.value() : null;
    }

    /** After a lost same-key race: re-read the committed winner transaction in a fresh transaction. */
    private Transaction loadTransactionByKey(String rk, UniqueViolationException cause) {
        if (rk == null) {
            throw cause;
        }
        return tm.run(conn -> ledger.findByRequestKey(rk, conn)
                .orElseThrow(() -> new DataAccessException("idempotent transaction not found after race: " + rk, cause)));
    }

    /** After a lost same-key race on createAccount: re-read the committed winner account. */
    private Account loadAccountByKey(String rk, UniqueViolationException cause) {
        return tm.run(conn -> accounts.findByRequestKey(rk, conn)
                .orElseThrow(() -> new DataAccessException("idempotent account not found after race: " + rk, cause)));
    }

    private Transaction record(TransactionId id, TransactionType type, List<LedgerEntry> entries) {
        return Transaction.create(id, transactionSequence.incrementAndGet(), clock.instant(), type, entries);
    }

    private static void requirePositiveAmount(Money amount) {
        if (!amount.isPositive()) {
            throw new NegativeAmountException("Amount must be strictly positive: " + amount);
        }
    }

    private static void requireSameCurrency(Currency amountCurrency, Currency accountCurrency) {
        if (!amountCurrency.equals(accountCurrency)) {
            throw new CurrencyMismatchException(accountCurrency, amountCurrency);
        }
    }
}
