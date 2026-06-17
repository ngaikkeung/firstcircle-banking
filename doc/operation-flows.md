# Operation flows

Step-by-step flows for each `BankingService` operation, plus the two cross-cutting concerns —
concurrency (transactions + row locks) and idempotency. Every mutating operation is **atomic**:
all balance changes and the ledger posting commit together in one database transaction, or — on
any failure — the whole transaction rolls back, leaving nothing behind.

## Deposit

```mermaid
sequenceDiagram
    participant C as Caller
    participant S as BankingService
    participant TM as TransactionManager
    participant R as AccountRepository
    participant G as LedgerRepository

    C->>S: deposit(accountId, amount[, key])
    S->>TM: run(tx body)
    TM->>TM: BEGIN (autoCommit=false)
    opt key provided
        S->>G: findByRequestKey(key)
        alt key exists
            S-->>C: return original transaction (replay)
        else key absent
            Note over S,G: append will carry request_key (UNIQUE) — the at-most-once gate
        end
    end
    S->>S: requirePositiveAmount(amount)
    S->>R: findForUpdate(accountId)
    Note over R: SELECT ... FOR UPDATE (row locked until commit)
    S->>S: requireSameCurrency(amount, account)
    S->>S: account = account.credit(amount.minor)
    S->>R: update(account)
    S->>G: append(Transaction:<br/>credit account / debit CASH_CONTRA, request_key)
    TM->>TM: COMMIT
    S-->>C: Transaction
```

## Withdrawal

Same shape as deposit, but the sufficiency check runs **with the row locked** so a concurrent
withdrawal cannot overdraw:

```mermaid
sequenceDiagram
    participant C as Caller
    participant S as BankingService
    participant TM as TransactionManager
    participant R as AccountRepository
    participant G as LedgerRepository

    C->>S: withdraw(accountId, amount)
    S->>TM: run(tx body)
    TM->>TM: BEGIN
    S->>S: requirePositiveAmount(amount)
    S->>R: findForUpdate(accountId)
    S->>S: requireSameCurrency(amount, account)
    alt balance >= amount
        S->>S: account = account.debit(amount.minor)
        S->>R: update(account)
        S->>G: append(Transaction:<br/>debit account / credit CASH_CONTRA)
        TM->>TM: COMMIT
        S-->>C: Transaction
    else insufficient
        Note over S: throw InsufficientFundsException
        TM->>TM: ROLLBACK (balance unchanged, nothing appended)
    end
```

## Transfer — same currency

```mermaid
sequenceDiagram
    participant C as Caller
    participant S as BankingService
    participant TM as TransactionManager
    participant R as AccountRepository
    participant G as LedgerRepository

    C->>S: transfer(from, to, amount)
    S->>TM: run(tx body)
    TM->>TM: BEGIN
    S->>S: amount > 0, from != to
    S->>S: order ids [from, to] by AccountId.compareTo
    S->>R: findForUpdate(first), findForUpdate(second)
    Note over R: rows locked in canonical order → deadlock-free
    S->>S: requireSameCurrency(amount, source)
    S->>S: check source.balance >= amount
    S->>S: source.debit / destination.credit (same minor units)
    S->>R: update(source), update(destination)
    S->>G: append(Transaction:<br/>debit source / credit destination)
    TM->>TM: COMMIT
    S-->>C: Transaction
```

## Transfer — cross currency (FX)

When source and destination currencies differ, the amount is converted at the spot rate and the
FX contra absorbs the position (and any rounding residue) so each currency leg balances
independently:

```mermaid
sequenceDiagram
    participant C as Caller
    participant S as BankingService
    participant TM as TransactionManager
    participant F as ExchangeRateProvider
    participant R as AccountRepository
    participant G as LedgerRepository

    C->>S: transfer(from, to, amount)
    S->>TM: run(tx body)
    TM->>TM: BEGIN
    S->>R: findForUpdate(from), findForUpdate(to)
    S->>S: requireSameCurrency(amount, source)
    S->>F: rate(source.currency, destination.currency)
    alt rate missing
        Note over S: throw FxRateUnavailableException → ROLLBACK (nothing mutated)
    end
    S->>S: amount.convert(dest.currency, rate) → destAmount (HALF_UP)
    S->>S: source.debit(sourceMinor), destination.credit(destMinor)
    S->>R: update(source), update(destination)
    S->>G: append(Transaction:<br/>debit source + credit FX_CONTRA (source ccy)<br/>debit FX_CONTRA + credit destination (dest ccy))
    TM->>TM: COMMIT
    S-->>C: Transaction
```

## Account creation & balance

```mermaid
sequenceDiagram
    participant C as Caller
    participant S as BankingService
    participant TM as TransactionManager
    participant R as AccountRepository
    participant G as LedgerRepository

    C->>S: createAccount(owner, currency, initialDeposit)
    S->>TM: run(tx body)
    TM->>TM: BEGIN
    S->>S: initialDeposit.currency == currency
    S->>S: new Account(randomId, ...)
    S->>R: insert(account)
    opt initialDeposit > 0
        S->>G: append(Transaction type=CREATE:<br/>credit account / debit CASH_CONTRA)
    end
    TM->>TM: COMMIT
    S-->>C: Account
    Note over C,S: getBalance(id): run tx → findById → balance()
```

A zero opening deposit creates the account but posts no ledger transaction.

## Concurrency: ordered row locking

Accounts are always locked (`SELECT … FOR UPDATE`) in `AccountId.compareTo` order. Two transfers
running in opposite directions between the same accounts therefore request the **same** lock
sequence — there is no cyclic wait, so the system cannot deadlock. Locks are held until the
transaction commits or rolls back.

```mermaid
flowchart LR
    subgraph T1["Tx 1: transfer A → B"]
        T1A["order [A,B]"] --> T1L1["FOR UPDATE A"]
        T1L1 --> T1L2["FOR UPDATE B"]
        T1L2 --> T1W["work + commit"]
    end
    subgraph T2["Tx 2: transfer B → A"]
        T2A["order [A,B]"] --> T2L1["FOR UPDATE A"]
        T2L1 --> T2L2["FOR UPDATE B"]
        T2L2 --> T2W["work + commit"]
    end

    T1L1 -.->|blocks T2L1 until commit| T2L1
```

Self-transfers are de-duplicated before locking (the id set has one element), so a single row is
locked. H2's MVCC (on by default in 2.x) provides row-level locking for `FOR UPDATE`; a generous
`LOCK_TIMEOUT` ensures a contended lock waits rather than failing fast — canonical ordering means
the wait always resolves.

## Idempotency: at-most-once execution

Mutating operations accept an optional `IdempotencyKey`. The key is stored as a `UNIQUE` column on
the entity the operation produced (`accounts.request_key` for createAccount, `transactions.request_key`
otherwise). The service checks by key first and **returns the original result** on a hit (a retry
never re-executes); on a concurrent same-key race the loser's whole transaction rolls back and it
re-reads the winner. Conflict detection for a key reused with **different** parameters is not
provided — a duplicate simply returns the original.

```mermaid
flowchart TD
    Start([op with key]) --> Try["tm.run: findByRequestKey(key)"]
    Try --> Exists?{"key exists?"}
    Exists? -->|"yes"| Replay([return original result])
    Exists? -->|"no"| Work["execute operation, insert/append with request_key"]
    Work --> Race?{"UNIQUE violated<br/>on commit?"}
    Race? -->|"no"| Done([commit, return result])
    Race? -->|"yes (lost race)"| Roll["tx rolled back"] --> Read["fresh tx: findByRequestKey → winner"]
    Read --> Replay
```

Because the losing transaction is rolled back before the re-read, a balance is never applied twice.
Operations called without a key insert `NULL` (NULLs are distinct in UNIQUE, so they never collide)
and always execute.
