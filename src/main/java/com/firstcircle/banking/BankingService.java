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
import com.firstcircle.banking.exceptions.IdempotencyConflictException;
import com.firstcircle.banking.exceptions.InsufficientFundsException;
import com.firstcircle.banking.exceptions.NegativeAmountException;
import com.firstcircle.banking.exceptions.SameAccountTransferException;
import com.firstcircle.banking.fx.ExchangeRateProvider;
import com.firstcircle.banking.idempotency.IdempotencyKey;
import com.firstcircle.banking.idempotency.IdempotencyRecord;
import com.firstcircle.banking.idempotency.IdempotencyRepository;
import com.firstcircle.banking.idempotency.IdempotencyResultKind;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * The public façade for basic banking operations: account creation, deposit, withdrawal,
 * transfer, and balance enquiry.
 *
 * <p>This service is the single place that coordinates everything required to keep money
 * correct and auditable:
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
 *   <li><b>Idempotency</b> — mutating operations can be made at-most-once via an
 *       {@link IdempotencyKey}; the key is claimed inside the same transaction, so retries return
 *       the original result without re-executing.</li>
 * </ul>
 *
 * <p>All mutating operations are atomic: either the whole operation (balance changes + ledger
 * posting) commits, or none of it is visible. No overdraft is ever permitted.
 */
public final class BankingService {

    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final ExchangeRateProvider fx;
    private final TransactionManager tm;
    private final IdempotencyRepository idem;
    private final Clock clock;
    private final AtomicLong transactionSequence = new AtomicLong(0L);

    public BankingService(AccountRepository accounts, LedgerRepository ledger,
                          ExchangeRateProvider fx, TransactionManager tm,
                          IdempotencyRepository idem, Clock clock) {
        this.accounts = Objects.requireNonNull(accounts);
        this.ledger = Objects.requireNonNull(ledger);
        this.fx = Objects.requireNonNull(fx);
        this.tm = Objects.requireNonNull(tm);
        this.idem = Objects.requireNonNull(idem);
        this.clock = Objects.requireNonNull(clock);
    }

    // ---------------------------------------------------------------- create

    public Account createAccount(String ownerName, Currency currency, Money initialDeposit) {
        return createAccount(ownerName, currency, initialDeposit, null);
    }

    public Account createAccount(String ownerName, Currency currency, Money initialDeposit, IdempotencyKey key) {
        Objects.requireNonNull(ownerName, "ownerName");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(initialDeposit, "initialDeposit");
        String fingerprint = "CREATE|" + ownerName + "|" + currency.getCurrencyCode() + "|" + initialDeposit.minor();
        return tm.run(conn -> doCreateAccount(conn, ownerName, currency, initialDeposit, key, fingerprint));
    }

    private Account doCreateAccount(Connection conn, String ownerName, Currency currency,
                                    Money initialDeposit, IdempotencyKey key, String fingerprint) {
        AccountId id = AccountId.random();
        if (key != null) {
            Optional<Account> replay = resolveOrClaim(conn, key, fingerprint, id.value(),
                    IdempotencyResultKind.ACCOUNT,
                    ref -> accounts.findById(AccountId.of(ref), conn)
                            .orElseThrow(() -> new DataAccessException("idempotent account missing: " + ref)));
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        if (!initialDeposit.currency().equals(currency)) {
            throw new CurrencyMismatchException(currency, initialDeposit.currency());
        }
        Account account = new Account(id, ownerName, currency, initialDeposit.minor());
        accounts.insert(account, conn);
        if (initialDeposit.isPositive()) {
            Transaction tx = record(TransactionId.random(), TransactionType.CREATE, List.of(
                    LedgerEntry.credit(account.id(), currency, initialDeposit.minor()),
                    LedgerEntry.debit(ContraAccountIds.CASH_CONTRA, currency, initialDeposit.minor())));
            ledger.append(tx, conn);
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
        String fingerprint = depositFingerprint(id, amount);
        return tm.run(conn -> doDeposit(conn, id, amount, key, fingerprint));
    }

    private Transaction doDeposit(Connection conn, AccountId id, Money amount,
                                  IdempotencyKey key, String fingerprint) {
        TransactionId txId = TransactionId.random();
        if (key != null) {
            Optional<Transaction> replay = resolveOrClaim(conn, key, fingerprint, txId.value(),
                    IdempotencyResultKind.TRANSACTION, ref -> loadTransaction(conn, ref));
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        requirePositiveAmount(amount);
        Account account = accounts.findForUpdate(id, conn).orElseThrow(() -> new AccountNotFoundException(id));
        requireSameCurrency(amount.currency(), account.currency());
        account = account.credit(amount.minor());
        Transaction tx = record(txId, TransactionType.DEPOSIT, List.of(
                LedgerEntry.credit(account.id(), account.currency(), amount.minor()),
                LedgerEntry.debit(ContraAccountIds.CASH_CONTRA, account.currency(), amount.minor())));
        accounts.update(account, conn);
        ledger.append(tx, conn);
        return tx;
    }

    // ---------------------------------------------------------------- withdraw

    public Transaction withdraw(AccountId id, Money amount) {
        return withdraw(id, amount, null);
    }

    public Transaction withdraw(AccountId id, Money amount, IdempotencyKey key) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(amount, "amount");
        String fingerprint = withdrawFingerprint(id, amount);
        return tm.run(conn -> doWithdraw(conn, id, amount, key, fingerprint));
    }

    private Transaction doWithdraw(Connection conn, AccountId id, Money amount,
                                   IdempotencyKey key, String fingerprint) {
        TransactionId txId = TransactionId.random();
        if (key != null) {
            Optional<Transaction> replay = resolveOrClaim(conn, key, fingerprint, txId.value(),
                    IdempotencyResultKind.TRANSACTION, ref -> loadTransaction(conn, ref));
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        requirePositiveAmount(amount);
        Account account = accounts.findForUpdate(id, conn).orElseThrow(() -> new AccountNotFoundException(id));
        requireSameCurrency(amount.currency(), account.currency());
        if (account.balanceMinor() < amount.minor()) {
            throw new InsufficientFundsException(account.id(), account.balanceMinor(), amount.minor());
        }
        account = account.debit(amount.minor());
        Transaction tx = record(txId, TransactionType.WITHDRAWAL, List.of(
                LedgerEntry.debit(account.id(), account.currency(), amount.minor()),
                LedgerEntry.credit(ContraAccountIds.CASH_CONTRA, account.currency(), amount.minor())));
        accounts.update(account, conn);
        ledger.append(tx, conn);
        return tx;
    }

    // ---------------------------------------------------------------- transfer

    public Transaction transfer(AccountId from, AccountId to, Money amount) {
        return transfer(from, to, amount, null);
    }

    public Transaction transfer(AccountId from, AccountId to, Money amount, IdempotencyKey key) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(amount, "amount");
        String fingerprint = transferFingerprint(from, to, amount);
        return tm.run(conn -> doTransfer(conn, from, to, amount, key, fingerprint));
    }

    private Transaction doTransfer(Connection conn, AccountId from, AccountId to, Money amount,
                                   IdempotencyKey key, String fingerprint) {
        requirePositiveAmount(amount);
        if (from.equals(to)) {
            throw new SameAccountTransferException();
        }
        TransactionId txId = TransactionId.random();
        if (key != null) {
            Optional<Transaction> replay = resolveOrClaim(conn, key, fingerprint, txId.value(),
                    IdempotencyResultKind.TRANSACTION, ref -> loadTransaction(conn, ref));
            if (replay.isPresent()) {
                return replay.get();
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
            Transaction tx = record(txId, TransactionType.TRANSFER, List.of(
                    LedgerEntry.debit(source.id(), source.currency(), sourceMinor),
                    LedgerEntry.credit(destination.id(), destination.currency(), sourceMinor)));
            accounts.update(source, conn);
            accounts.update(destination, conn);
            ledger.append(tx, conn);
            return tx;
        }

        // Cross-currency: convert at the spot rate; the FX contra absorbs the position and any
        // rounding residue so each currency leg independently nets to zero.
        BigDecimal rate = fx.rate(source.currency(), destination.currency()); // throws if unavailable
        long destinationMinor = amount.convert(destination.currency(), rate).minor();

        source = source.debit(sourceMinor);
        destination = destination.credit(destinationMinor);
        Transaction tx = record(txId, TransactionType.TRANSFER, List.of(
                LedgerEntry.debit(source.id(), source.currency(), sourceMinor),
                LedgerEntry.credit(ContraAccountIds.FX_CONTRA, source.currency(), sourceMinor),
                LedgerEntry.debit(ContraAccountIds.FX_CONTRA, destination.currency(), destinationMinor),
                LedgerEntry.credit(destination.id(), destination.currency(), destinationMinor)));
        accounts.update(source, conn);
        accounts.update(destination, conn);
        ledger.append(tx, conn);
        return tx;
    }

    // ---------------------------------------------------------------- balance

    public Money getBalance(AccountId id) {
        Objects.requireNonNull(id, "id");
        return tm.run(conn -> accounts.findById(id, conn)
                .orElseThrow(() -> new AccountNotFoundException(id))
                .balance());
    }

    /** Exposed for tests/auditing: every posted transaction, in sequence order. */
    public List<Transaction> ledger() {
        return tm.run(ledger::findAll);
    }

    // ---------------------------------------------------------------- helpers

    private Transaction loadTransaction(Connection conn, UUID txId) {
        return ledger.findById(TransactionId.of(txId), conn)
                .orElseThrow(() -> new DataAccessException("idempotent transaction missing: " + txId));
    }

    /**
     * Idempotency fast-path + race backstop. Returns the previously-stored result (replay) or claims
     * the key and returns empty so the caller may proceed. Claiming happens <em>before</em> the work,
     * so a request that loses the race has written nothing and simply returns the winner's result.
     */
    private <T> Optional<T> resolveOrClaim(Connection conn, IdempotencyKey key, String fingerprint,
                                           UUID resultRef, IdempotencyResultKind kind,
                                           Function<UUID, T> loader) {
        Optional<IdempotencyRecord> existing = idem.findByKey(key, conn);
        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            ensureFingerprintMatches(key, record, fingerprint);
            return Optional.of(loader.apply(record.resultRef()));
        }
        try {
            idem.claim(key, fingerprint, kind, resultRef, conn);
            return Optional.empty();
        } catch (UniqueViolationException e) {
            // A concurrent request claimed this key first; load its committed result.
            IdempotencyRecord record = idem.findByKey(key, conn)
                    .orElseThrow(() -> new DataAccessException("idempotency race: claimed key row not found"));
            ensureFingerprintMatches(key, record, fingerprint);
            return Optional.of(loader.apply(record.resultRef()));
        }
    }

    private static void ensureFingerprintMatches(IdempotencyKey key, IdempotencyRecord record, String fingerprint) {
        if (!record.fingerprint().equals(fingerprint)) {
            throw new IdempotencyConflictException(key, record.fingerprint(), fingerprint);
        }
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

    private Transaction record(TransactionId id, TransactionType type, List<LedgerEntry> entries) {
        return Transaction.create(id, transactionSequence.incrementAndGet(), clock.instant(), type, entries);
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
