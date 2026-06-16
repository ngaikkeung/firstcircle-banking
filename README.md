# Banking Service

A **banking demo** in **Java 17** (no HTTP/API layer) implementing account management,
deposits, no-overdraft withdrawals, multi-currency transfers, and balance enquiries. It enforces
exact monetary representation and a per-currency balanced double-entry ledger, with all operations
safe under concurrent access.

**Highlights**

- **Exact money** — signed `long` minor units; `BigDecimal` used only at the FX boundary
- **Balanced by construction** — every ledger transaction nets to zero, per currency
- **Deadlock-free concurrency** — ordered, per-account locking
- **Ports & adapters** — in-memory today, persistence-ready with no domain changes
- **At-most-once mutations** — idempotency keys make retries safe
- **Well-tested** — JUnit 5 + AssertJ, including concurrency stress tests

---

## User stories

- **As a prospective account holder**, I want to open an account — optionally funded with an
  opening deposit — so that I can start using banking services.
- **As an account holder**, I want to deposit money so that my balance grows by exactly the
  amount I deposited.
- **As an account holder**, I want to withdraw money, and never be allowed to overdraw, so that I
  can access my funds without going into debt.
- **As an account holder**, I want to transfer money to another account in my own currency or a
  different one so that I can pay others reliably.
- **As an account holder**, I want to check my balance at any time so that I always know how much
  money I have.
- **As an integrating application**, I want every operation to be safe under concurrent access and
  replayable under the same idempotency key without side effects, so that retries and parallel
  traffic can never corrupt balances or create duplicate transactions.
- **As an auditor**, I want every money movement recorded in a balanced double-entry ledger so
  that funds are always traceable and fully accounted for.

---

## Requirement & coverage

| # | Requirement | Fulfilled? | How |
|---|---|---|---|
| 1 | Account creation with an initial deposit | ✅ | `createAccount(owner, currency, initialDeposit)` — zero or positive opening deposit; a non-zero opening posts a balanced ledger entry |
| 2 | Deposit money into an account | ✅ | `deposit(accountId, money)` |
| 3 | Withdraw money, **no overdraft** | ✅ | `withdraw(accountId, money)` — balance checked under the account lock; throws `InsufficientFundsException` rather than overdrawing |
| 4 | Transfer funds between accounts | ✅ | `transfer(from, to, money)` — same-currency and cross-currency (FX conversion) |
| 5 | Check the account balance | ✅ | `getBalance(accountId)` — read under the account lock |
| 6 | In-memory storage (no DB required) | ✅ | `ConcurrentHashMap`-backed adapters; no external database |
| 7 | "Service" = component/module, no API needed | ✅ | No HTTP/REST — a Java module exposing `BankingService` |
| — | Real-world bank constraints | ✅ | Exact money (`long` minor units, no floats), double-entry ledger (per-currency zero-sum), thread-safety (deadlock-free per-account locks), money conservation, overdraft prevention under concurrency |
| — | Engineering best practices | ✅ | Ports & adapters (persistence-ready), immutable value objects, exception hierarchy, 51 tests incl. concurrency stress, injected `Clock`, Lombok, Mermaid docs |

All requirements are met. Beyond the minimum, the implementation also adds multi-currency FX,
idempotency keys (replay protection), and a fully auditable ledger — see
[Design decisions](#design-decisions) and [Known limitations](#out-of-scope--future-enhancements).

---

## Quick start

Requires **JDK 17** and **Maven**.

```bash
mvn clean test                      # compile + run the full test suite (incl. concurrency stress)
mvn test -Dtest=ConcurrencyStressTest   # run the concurrency suite alone
```

Run the demo (a multi-currency end-to-end flow):

```bash
mvn -q exec:java -Dexec.mainClass=com.firstcircle.banking.demo.Demo
```

---

## Documentation

Deeper design write-ups with Mermaid diagrams live under [`doc/`](doc/) — see
[`doc/README.md`](doc/README.md) for the full index:

- [System overview](doc/system-overview.md) — architecture, layering, ports & adapters
- [Entity / data model](doc/entities.md) — domain objects, fields, relationships, invariants
- [Operation flows](doc/operation-flows.md) — sequence diagrams for each operation + concurrency/idempotency
- [Ledger design](doc/ledger.md) — double-entry model, per-currency balancing, contra accounts
- [Money movement](doc/money-movement.md) — conservation, FX rounding residue
- [AI usage](doc/ai-usage.md) — how Claude Code was used across the development cycle

---

## What it does

| Operation | Behaviour |
|---|---|
| `createAccount(owner, currency, initialDeposit)` | Opens an account; initial deposit may be zero but must match the account currency. |
| `deposit(accountId, money)` | Credits the account; the money's currency must match. |
| `withdraw(accountId, money)` | Debits the account; **never allowed to overdraw**. |
| `transfer(from, to, money)` | Moves funds between two accounts. Same-currency is direct; cross-currency converts at a spot rate. |
| `getBalance(accountId)` | Returns the current balance in the account's currency. |

Every mutating operation has an overload that accepts an **idempotency key**
(`deposit(accountId, money, key)`) so retries are safe.

Each mutation also returns / records the ledger `Transaction` it produced.

---

## Design decisions

### Money — `long` minor units + `BigDecimal` FX (`domain/Money.java`)
Money is stored as signed `long` minor units (e.g. HKD 12.34 == `1234`). This is exact (no
floating point), fast, and trivially safe to read concurrently. `BigDecimal` is used **only** at
the foreign-exchange boundary, where fractional values are unavoidable and an explicit rounding
policy is required (`HALF_UP` to the target currency's minor units). `Money` is immutable; every
operation returns a new value, and a negative amount can never be constructed.

### Double-entry ledger (`domain/Transaction.java`, `domain/LedgerEntry.java`)
Every transaction is a set of signed `LedgerEntry`s that must **net to zero, per currency**. This
invariant is enforced in `Transaction.create`, so an unbalanced transaction cannot exist in the
system. Deposits and withdrawals book against a cash contra account; cross-currency transfers book
against an FX contra account, which absorbs the rounding residue so each currency leg balances
independently. Contra accounts are well-known internal ids in `ledger/ContraAccountIds`.

### Concurrency — deadlock-free per-account locks (`concurrency/LockManager.java`)
Each `AccountId` owns a `ReentrantLock`. `LockManager.acquireOrdered` de-duplicates and sorts the
ids (`AccountId` is `Comparable`) and locks them in that single global order, so two transfers
between the same pair of accounts can never deadlock. Locks are released in reverse order in a
`finally`. Balances are read and written only while the account's lock is held, which prevents
lost updates, torn reads, and overdrafts under contention.

**Why not optimistic CAS / a global lock?** There is no lock-free two-key CAS in plain Java, so a
CAS design would re-invent locking poorly under contention. A global lock is correct but a
throughput disaster. Pessimistic per-account locks are provably deadlock-free with ordering and —
crucially — map directly onto `SELECT ... FOR UPDATE` ordering when a real database is added.

**Accounts are immutable values**: `credit`/`debit` return a new `Account`. The per-account lock
serialises the *load → compute → store* of that account's repository entry; replacing the instance
on each update is safe because the lock is keyed by the stable `AccountId`.

### Ports & adapters (persistence-ready)
`AccountRepository`, `LedgerRepository`, and `ExchangeRateProvider` are interfaces backed by
in-memory implementations. Swapping in a real database means writing new adapters — no service or
domain code changes. Locking lives in `BankingService`, **not** the repositories: multi-account
atomicity is a service-level concern.

### Idempotency (`idempotency/`)
Mutating operations can be made at-most-once with an `IdempotencyKey`. The store claims a key
atomically; the first request executes and stores the result (success **or** the thrown business
exception), and concurrent/replayed duplicates with the same key return that stored result without
re-executing. Reusing a key with *different* parameters throws `IdempotencyConflictException` to
catch misuse. (The in-memory store is session-scoped; production would use a durable shared store.)

---

## Validation & edge-case policy

| Case | Behaviour |
|---|---|
| Initial deposit of zero | **Allowed.** |
| Negative initial deposit | Rejected (`NegativeAmountException`). |
| Zero/negative deposit, withdrawal, or transfer | Rejected (`NegativeAmountException`). |
| Self-transfer | Rejected (`SameAccountTransferException`). |
| Overdraft | **Never allowed** (`InsufficientFundsException`); checked under lock. |
| Wrong-currency amount vs. account | Rejected (`CurrencyMismatchException`). |
| Missing FX rate | Rejected (`FxRateUnavailableException`); the transfer does not mutate anything. |
| Account currency | Immutable after creation. |
| FX rounding | `HALF_UP` to destination minor units; residue absorbed by the FX contra account. |

---

## Project layout

```
src/main/java/com/firstcircle/banking/
  BankingService.java        # public façade: orchestrates locking, FX, ledger, idempotency
  domain/                    # Money, Account (immutable), AccountId, Transaction, LedgerEntry, factories
  exceptions/                # BankingException + 7 specific subclasses
  fx/                        # ExchangeRateProvider port + in-memory adapter
  idempotency/               # IdempotencyKey, store port + in-memory adapter
  ledger/                    # ContraAccountIds (system bookkeeping accounts)
  repo/                      # Account/Ledger repository ports + in-memory adapters
  concurrency/               # LockManager (ordered, deadlock-free locking)
  demo/                      # Demo.main() end-to-end example
src/test/java/com/firstcircle/banking/
  domain/                    # MoneyTest, TransactionBalanceTest
  fx/                        # FxConversionTest
  idempotency/               # IdempotencyTest
  concurrency/               # ConcurrencyStressTest (overdraft race, lost-update, conservation, ping-pong)
  BankingService*Test.java   # per-operation behaviour + edge cases
```

## Dependencies

Production code depends only on the JDK plus **Lombok** (compile-time only, `provided` scope),
which generates the value objects' getters, constructors, `equals`/`hashCode`, and `toString`.
A project-wide `lombok.config` sets fluent accessors (`minor()`, `currency()`, `id()`, …) so the
generated methods match the hand-written API. Validation logic and currency-aware formatting are
kept explicit. Tests use JUnit 5 and AssertJ.

---

## Testing

- **Unit tests** for `Money` (arithmetic, FX rounding incl. zero-decimal currencies) and
  `Transaction` (factory rejects any entry set that doesn't net to zero per currency).
- **Service tests** for create / deposit / withdraw / transfer / balance: happy paths plus every
  error path (currency mismatch, unknown account, insufficient funds, negative/zero, self-transfer,
  cross-currency FX with residue, missing rate). Each asserts the posted ledger is balanced.
- **Idempotency tests**: replay returns the identical transaction; two concurrent same-key calls
  execute once; same key + different params conflicts.
- **Concurrency stress tests** (`ExecutorService` + `CountDownLatch`, `@Timeout`): an overdraft
  race (exactly N succeed, balance hits zero), a lost-update deposit check (balance == start + Σ),
  closed-system money conservation across many transfers, and a ping-pong pair that must complete
  without deadlock. A post-run assertion confirms every ledger transaction is still per-currency
  balanced (no partial writes).

---

## Out of scope / future enhancements

- A real persistence adapter (JDBC/JPA with `SELECT ... FOR UPDATE` mirroring the lock order).
- A durable, shared idempotency store (Redis / DB).
- Authentication & authorisation, interest, fees, holds, event publishing.
- `long` minor units comfortably cover realistic balances; if sub-unit precision or
  extremely large values were ever needed, a `BigDecimal`-backed `Money` is a drop-in alternative.
