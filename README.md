# Banking Service

A **banking demo** in **Java 17** (no HTTP/API layer) implementing account management,
deposits, no-overdraft withdrawals, multi-currency transfers, and balance enquiries. It enforces
exact monetary representation and a per-currency balanced double-entry ledger, with all operations
safe under concurrent access.

**Highlights**

- **Exact money** — signed `long` minor units; `BigDecimal` used only at the FX boundary
- **Balanced by construction** — every ledger transaction nets to zero, per currency
- **Deadlock-free concurrency** — DB transactions + `SELECT … FOR UPDATE` in canonical account order
- **H2-backed storage** — in-memory relational DB; ACID transactions and a SQL-queryable ledger
- **At-most-once mutations** — idempotency key stored as a `UNIQUE` column on the entity (DB-enforced)
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

| # | Requirement                                 | Fulfilled? | How                                                                                                                                                                                                   |
|---|---------------------------------------------|------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1 | Account creation with an initial deposit    | ✅          | `createAccount(owner, currency, initialDeposit)` — zero or positive opening deposit; a non-zero opening posts a balanced ledger entry                                                                 |
| 2 | Deposit money into an account               | ✅          | `deposit(accountId, money)`                                                                                                                                                                           |
| 3 | Withdraw money, **no overdraft**            | ✅          | `withdraw(accountId, money)` — balance checked with the row locked; throws `InsufficientFundsException` rather than overdrawing                                                                       |
| 4 | Transfer funds between accounts             | ✅          | `transfer(from, to, money)` — same-currency and cross-currency (FX conversion)                                                                                                                        |
| 5 | Check the account balance                   | ✅          | `getBalance(accountId)` — snapshot read                                                                                                                                                               |
| 6 | In-memory storage (no DB required)          | ✅          | H2 in-memory relational DB (no external server); ACID transactions                                                                                                                                    |
| 7 | "Service" = component/module, no API needed | ✅          | No HTTP/REST — a Java module exposing `BankingService`                                                                                                                                                |
| — | Real-world bank constraints                 | ✅          | Exact money (`long` minor units, no floats), double-entry ledger (per-currency zero-sum), thread-safety (DB row locks in canonical order), money conservation, overdraft prevention under concurrency |
| — | Engineering best practices                  | ✅          | Ports & adapters, immutable value objects, exception hierarchy, 53 tests incl. concurrency stress, injected `Clock`, Lombok, Mermaid docs                                                             |

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

| Operation                                        | Behaviour                                                                                          |
|--------------------------------------------------|----------------------------------------------------------------------------------------------------|
| `createAccount(owner, currency, initialDeposit)` | Opens an account; initial deposit may be zero but must match the account currency.                 |
| `deposit(accountId, money)`                      | Credits the account; the money's currency must match.                                              |
| `withdraw(accountId, money)`                     | Debits the account; **never allowed to overdraw**.                                                 |
| `transfer(from, to, money)`                      | Moves funds between two accounts. Same-currency is direct; cross-currency converts at a spot rate. |
| `getBalance(accountId)`                          | Returns the current balance in the account's currency.                                             |

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

### Concurrency — DB transactions + `SELECT … FOR UPDATE` (`db/TransactionManager.java`)

Every operation runs in one database transaction. Accounts are locked with
`SELECT … FOR UPDATE`, acquired in a single canonical `AccountId` order (the ids are de-duplicated
and sorted, since `AccountId` is `Comparable`), so two transfers over the same pair of accounts can
never deadlock. The transaction commits the balance changes and the ledger posting together, so an
operation can never be observed half-applied — no lost updates, no torn reads, no overdrafts under
contention. H2's MVCC (on by default in 2.x) gives row-level locking for the `FOR UPDATE`.

**Why not optimistic CAS / a global lock?** There is no lock-free two-key CAS in plain Java, so a
CAS design would re-invent locking poorly under contention. A global lock is correct but a
throughput disaster. Pessimistic row locks in canonical order are provably deadlock-free and scale
to a real database unchanged — only the JDBC URL differs.

**Accounts are immutable values**: `credit`/`debit` return a new `Account`. The row lock serialises
the *read → compute → write* of that account's stored balance; updating the row on each operation
is safe because the lock is keyed by the stable `AccountId`.

### Ports & adapters (persistence-ready)

`AccountRepository`, `LedgerRepository`, and `ExchangeRateProvider` are interfaces backed by JDBC
adapters (the first two over H2). Swapping in Postgres/MySQL means writing new adapters against the
same ports — no service or domain code changes. Transactions and row locking live in
`BankingService` via `TransactionManager`, **not** the repositories: multi-account atomicity is a
service-level concern.

### Idempotency (`idempotency/`)

Mutating operations can be made at-most-once with an `IdempotencyKey`. The request key is stored as
a `UNIQUE` column on the entity the operation produced (`accounts.request_key` for `createAccount`,
`transactions.request_key` for deposit/withdraw/transfer). A duplicate key returns the original
result without re-executing; on a concurrent same-key race the loser's transaction rolls back and it
re-reads the winner. Conflict detection for a key reused with *different* parameters is intentionally
not provided — a duplicate simply returns the original.

---

## Validation & edge-case policy

| Case                                           | Behaviour                                                                        |
|------------------------------------------------|----------------------------------------------------------------------------------|
| Initial deposit of zero                        | **Allowed.**                                                                     |
| Negative initial deposit                       | Rejected (`NegativeAmountException`).                                            |
| Zero/negative deposit, withdrawal, or transfer | Rejected (`NegativeAmountException`).                                            |
| Self-transfer                                  | Rejected (`SameAccountTransferException`).                                       |
| Overdraft                                      | **Never allowed** (`InsufficientFundsException`); checked under lock.            |
| Wrong-currency amount vs. account              | Rejected (`CurrencyMismatchException`).                                          |
| Missing FX rate                                | Rejected (`FxRateUnavailableException`); the transfer does not mutate anything.  |
| Account currency                               | Immutable after creation.                                                        |
| FX rounding                                    | `HALF_UP` to destination minor units; residue absorbed by the FX contra account. |

---

## Project layout

```
src/main/java/com/firstcircle/banking/
  BankingService.java        # public interface (contract)
  DefaultBankingService.java # implementation: orchestrates transactions, FX, ledger, idempotency
  domain/                    # Money, Account (immutable), AccountId, Transaction, LedgerEntry, factories
  exceptions/                # BankingException + 6 specific subclasses
  fx/                        # ExchangeRateProvider port + in-memory adapter
  idempotency/               # IdempotencyKey (the client-supplied dedup token)
  ledger/                    # ContraAccountIds (system bookkeeping accounts)
  repo/                      # Account/Ledger repository ports + JDBC adapters
  db/                        # TransactionManager, DatabaseInitializer, H2DataSources (H2 in-memory)
  demo/                      # Demo.main() end-to-end example
src/main/resources/
  schema.sql                 # accounts, transactions, ledger_entries (request_key on accounts + transactions)
src/test/java/com/firstcircle/banking/
  domain/                    # MoneyTest, TransactionBalanceTest
  fx/                        # FxConversionTest
  idempotency/               # IdempotencyTest
  concurrency/               # ConcurrencyStressTest (overdraft race, lost-update, conservation, ping-pong)
  BankingService*Test.java   # per-operation behaviour + edge cases + tx-rollback
```

## Dependencies

Production code depends on the JDK plus **Lombok** (compile-time only, `provided` scope), which
generates the value objects' getters, constructors, `equals`/`hashCode`, and `toString`, and
**H2** (the in-memory database). A project-wide `lombok.config` sets fluent accessors
(`minor()`, `currency()`, `id()`, …) so the generated methods match the hand-written API. Validation
logic and currency-aware formatting are kept explicit. Tests use JUnit 5 and AssertJ.

---

## Testing

- **Unit tests** for `Money` (arithmetic, FX rounding incl. zero-decimal currencies) and
  `Transaction` (factory rejects any entry set that doesn't net to zero per currency).
- **Service tests** for create / deposit / withdraw / transfer / balance: happy paths plus every
  error path (currency mismatch, unknown account, insufficient funds, negative/zero, self-transfer,
  cross-currency FX with residue, missing rate). Each asserts the posted ledger is balanced.
- **Idempotency tests**: replay returns the identical transaction; two concurrent same-key calls
  execute once; same key + different params returns the original (no conflict).
- **Concurrency stress tests** (`ExecutorService` + `CountDownLatch`, `@Timeout`): an overdraft
  race (exactly N succeed, balance hits zero), a lost-update deposit check (balance == start + Σ),
  closed-system money conservation across many transfers, and a ping-pong pair that must complete
  without deadlock. A post-run assertion confirms every ledger transaction is still per-currency
  balanced (no partial writes).

---

## Out of scope / future enhancements

- A durable / server-side database: swap the H2 in-memory URL for H2 file mode or Postgres/MySQL
  (a JDBC URL change — the ports and adapters are unchanged).
- A schema-migration tool (Flyway) once DDL churn settles.
- Multi-instance coordination: H2 in-memory is single-JVM; clustering would need real distributed
  locking or an external database with `SELECT … FOR UPDATE`.
- Authentication & authorisation, interest, fees, holds, event publishing.
- `long` minor units comfortably cover realistic balances; if sub-unit precision or
  extremely large values were ever needed, a `BigDecimal`-backed `Money` is a drop-in alternative.
