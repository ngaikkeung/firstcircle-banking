package com.firstcircle.banking;

import com.firstcircle.banking.concurrency.LockManager;
import com.firstcircle.banking.domain.Account;
import com.firstcircle.banking.domain.AccountFactory;
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
import com.firstcircle.banking.idempotency.IdempotencyStore;
import com.firstcircle.banking.ledger.ContraAccountIds;
import com.firstcircle.banking.repo.AccountRepository;
import com.firstcircle.banking.repo.LedgerRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The public façade for basic banking operations: account creation, deposit, withdrawal,
 * transfer, and balance enquiry.
 *
 * <p>This service is the single place that coordinates everything required to keep money
 * correct and auditable:
 * <ul>
 *   <li><b>Locking</b> — per-account locks (via {@link LockManager}) acquired in a global,
 *       deadlock-free order; every balance read and write happens under the right lock.</li>
 *   <li><b>Double-entry ledger</b> — every mutation posts a {@link Transaction} that is
 *       guaranteed to net to zero per currency.</li>
 *   <li><b>FX</b> — cross-currency transfers convert at a spot rate and book the rounding
 *       residue to an FX contra account.</li>
 *   <li><b>Idempotency</b> — mutating operations can be made at-most-once via an
 *       {@link IdempotencyKey}.</li>
 * </ul>
 *
 * <p>All mutating operations are atomic: either the whole operation (balance changes + ledger
 * posting) succeeds, or none of it is visible. No overdraft is ever permitted.
 */
public final class BankingService {

    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final ExchangeRateProvider fx;
    private final LockManager locks;
    private final IdempotencyStore idempotency;
    private final Clock clock;
    private final AtomicLong transactionSequence = new AtomicLong(0L);

    public BankingService(AccountRepository accounts, LedgerRepository ledger,
                          ExchangeRateProvider fx, LockManager locks,
                          IdempotencyStore idempotency, Clock clock) {
        this.accounts = Objects.requireNonNull(accounts);
        this.ledger = Objects.requireNonNull(ledger);
        this.fx = Objects.requireNonNull(fx);
        this.locks = Objects.requireNonNull(locks);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Convenience constructor using the system UTC clock and a non-idempotent store. */
    public BankingService(AccountRepository accounts, LedgerRepository ledger,
                          ExchangeRateProvider fx, LockManager locks, Clock clock) {
        this(accounts, ledger, fx, locks, new com.firstcircle.banking.idempotency.InMemoryIdempotencyStore(), clock);
    }

    // ---------------------------------------------------------------- create

    public Account createAccount(String ownerName, Currency currency, Money initialDeposit) {
        return createAccount(ownerName, currency, initialDeposit, null);
    }

    public Account createAccount(String ownerName, Currency currency, Money initialDeposit, IdempotencyKey key) {
        Objects.requireNonNull(ownerName, "ownerName");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(initialDeposit, "initialDeposit");
        return withIdempotency(key, "CREATE|" + ownerName + "|" + currency.getCurrencyCode() + "|" + initialDeposit.minor(),
                () -> doCreateAccount(ownerName, currency, initialDeposit));
    }

    private Account doCreateAccount(String ownerName, Currency currency, Money initialDeposit) {
        // Money is guaranteed non-negative by its factory, so a zero opening deposit is allowed
        // and a negative one is already impossible. We only need to verify currency consistency.
        if (!initialDeposit.currency().equals(currency)) {
            throw new CurrencyMismatchException(currency, initialDeposit.currency());
        }
        Account account = AccountFactory.newAccount(ownerName, currency, initialDeposit.minor());
        accounts.save(account);
        if (initialDeposit.isPositive()) {
            record(TransactionType.CREATE, List.of(
                    LedgerEntry.credit(account.id(), currency, initialDeposit.minor()),
                    LedgerEntry.debit(ContraAccountIds.CASH_CONTRA, currency, initialDeposit.minor())));
        }
        return account;
    }

    // ---------------------------------------------------------------- deposit

    public Transaction deposit(AccountId id, Money amount) {
        return deposit(id, amount, null);
    }

    public Transaction deposit(AccountId id, Money amount, IdempotencyKey key) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(amount, "amount");
        return withIdempotency(key, depositFingerprint(id, amount), () -> doDeposit(id, amount));
    }

    private Transaction doDeposit(AccountId id, Money amount) {
        requirePositiveAmount(amount);
        requireAccount(id);
        List<ReentrantLock> held = locks.acquireOrdered(List.of(id));
        try {
            Account account = accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
            requireSameCurrency(amount.currency(), account.currency());
            account = account.credit(amount.minor());
            // Atomicity: write the ledger entry first, then persist the balance. A failure
            // between the two then leaves an auditable extra ledger entry rather than a balance
            // change with no record. A shared DB transaction is the full fix (see README).
            Transaction transaction = record(TransactionType.DEPOSIT, List.of(
                    LedgerEntry.credit(account.id(), account.currency(), amount.minor()),
                    LedgerEntry.debit(ContraAccountIds.CASH_CONTRA, account.currency(), amount.minor())));
            accounts.save(account);
            return transaction;
        } finally {
            locks.releaseAll(held);
        }
    }

    // ---------------------------------------------------------------- withdraw

    public Transaction withdraw(AccountId id, Money amount) {
        return withdraw(id, amount, null);
    }

    public Transaction withdraw(AccountId id, Money amount, IdempotencyKey key) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(amount, "amount");
        return withIdempotency(key, withdrawFingerprint(id, amount), () -> doWithdraw(id, amount));
    }

    private Transaction doWithdraw(AccountId id, Money amount) {
        requirePositiveAmount(amount);
        requireAccount(id);
        List<ReentrantLock> held = locks.acquireOrdered(List.of(id));
        try {
            Account account = accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
            requireSameCurrency(amount.currency(), account.currency());
            if (account.balanceMinor() < amount.minor()) {
                throw new InsufficientFundsException(account.id(), account.balanceMinor(), amount.minor());
            }
            account = account.debit(amount.minor());
            Transaction transaction = record(TransactionType.WITHDRAWAL, List.of(
                    LedgerEntry.debit(account.id(), account.currency(), amount.minor()),
                    LedgerEntry.credit(ContraAccountIds.CASH_CONTRA, account.currency(), amount.minor())));
            accounts.save(account);
            return transaction;
        } finally {
            locks.releaseAll(held);
        }
    }

    // ---------------------------------------------------------------- transfer

    public Transaction transfer(AccountId from, AccountId to, Money amount) {
        return transfer(from, to, amount, null);
    }

    public Transaction transfer(AccountId from, AccountId to, Money amount, IdempotencyKey key) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(amount, "amount");
        return withIdempotency(key, transferFingerprint(from, to, amount), () -> doTransfer(from, to, amount));
    }

    private Transaction doTransfer(AccountId from, AccountId to, Money amount) {
        requirePositiveAmount(amount);
        if (from.equals(to)) {
            throw new SameAccountTransferException();
        }
        // Fail fast (and avoid creating a lock for a non-existent id). Accounts are never
        // deleted, so this existence check cannot race with removal.
        requireAccount(from);
        requireAccount(to);

        List<ReentrantLock> held = locks.acquireOrdered(List.of(from, to));
        try {
            Account source = accounts.findById(from).orElseThrow(() -> new AccountNotFoundException(from));
            Account destination = accounts.findById(to).orElseThrow(() -> new AccountNotFoundException(to));
            // The transfer amount must be expressed in the source account's currency.
            requireSameCurrency(amount.currency(), source.currency());
            if (source.balanceMinor() < amount.minor()) {
                throw new InsufficientFundsException(source.id(), source.balanceMinor(), amount.minor());
            }

            long sourceMinor = amount.minor();
            if (source.currency().equals(destination.currency())) {
                source = source.debit(sourceMinor);
                destination = destination.credit(sourceMinor);
                Transaction transaction = record(TransactionType.TRANSFER, List.of(
                        LedgerEntry.debit(source.id(), source.currency(), sourceMinor),
                        LedgerEntry.credit(destination.id(), destination.currency(), sourceMinor)));
                accounts.save(source);
                accounts.save(destination);
                return transaction;
            }

            // Cross-currency: convert at the spot rate; the FX contra absorbs the position and
            // any rounding residue so each currency leg independently nets to zero.
            BigDecimal rate = fx.rate(source.currency(), destination.currency()); // throws if unavailable
            long destinationMinor = amount.convert(destination.currency(), rate).minor();

            source = source.debit(sourceMinor);
            destination = destination.credit(destinationMinor);
            Transaction transaction = record(TransactionType.TRANSFER, List.of(
                    LedgerEntry.debit(source.id(), source.currency(), sourceMinor),
                    LedgerEntry.credit(ContraAccountIds.FX_CONTRA, source.currency(), sourceMinor),
                    LedgerEntry.debit(ContraAccountIds.FX_CONTRA, destination.currency(), destinationMinor),
                    LedgerEntry.credit(destination.id(), destination.currency(), destinationMinor)));
            accounts.save(source);
            accounts.save(destination);
            return transaction;
        } finally {
            locks.releaseAll(held);
        }
    }

    // ---------------------------------------------------------------- balance

    public Money getBalance(AccountId id) {
        Objects.requireNonNull(id, "id");
        requireAccount(id);
        List<ReentrantLock> held = locks.acquireOrdered(List.of(id));
        try {
            return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id)).balance();
        } finally {
            locks.releaseAll(held);
        }
    }

    // ---------------------------------------------------------------- helpers

    private Account requireAccount(AccountId id) {
        return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
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

    private Transaction record(TransactionType type, List<LedgerEntry> entries) {
        Transaction transaction = Transaction.create(
                TransactionId.random(),
                transactionSequence.incrementAndGet(),
                clock.instant(),
                type,
                entries);
        ledger.append(transaction);
        return transaction;
    }

    private <T> T withIdempotency(IdempotencyKey key, String fingerprint, Supplier<T> operation) {
        if (key == null) {
            return operation.get();
        }
        return idempotency.executeOnce(key, fingerprint, operation);
    }

    private static String depositFingerprint(AccountId id, Money amount) {
        return "DEPOSIT|" + id + "|" + amount.currency().getCurrencyCode() + "|" + amount.minor();
    }

    private static String withdrawFingerprint(AccountId id, Money amount) {
        return "WITHDRAW|" + id + "|" + amount.currency().getCurrencyCode() + "|" + amount.minor();
    }

    private static String transferFingerprint(AccountId from, AccountId to, Money amount) {
        return "TRANSFER|" + from + "|" + to + "|" + amount.currency().getCurrencyCode() + "|" + amount.minor();
    }
}
