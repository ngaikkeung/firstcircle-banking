# Operation flows

Step-by-step flows for each `BankingService` operation, plus the two cross-cutting concerns —
concurrency (locking) and idempotency. Every mutating operation is **atomic**: all balance
changes and the ledger posting happen together, under the relevant account locks, or not at all.

## Deposit

```mermaid
sequenceDiagram
    participant C as Caller
    participant S as BankingService
    participant I as IdempotencyStore
    participant L as LockManager
    participant R as AccountRepository
    participant G as LedgerRepository

    C->>S: deposit(accountId, amount[, key])
    opt key provided
        S->>I: executeOnce(key, fingerprint, op)
        Note over I: at-most-once, replays return stored result
    end
    S->>S: requirePositiveAmount(amount)
    S->>R: findById(accountId)
    Note over S,R: fail-fast existence check (accounts are never deleted)
    S->>L: acquireOrdered([accountId])
    Note over L: lock the single account
    S->>R: findById(accountId)
    S->>S: requireSameCurrency(amount, account)
    S->>S: account = account.credit(amount.minor)
    S->>R: save(account)
    S->>G: append(Transaction:<br/>credit account / debit CASH_CONTRA)
    S->>L: releaseAll()
    S-->>C: Transaction
```

## Withdrawal

Same shape as deposit, but the sufficiency check runs **under the lock** so a concurrent
withdrawal cannot overdraw:

```mermaid
sequenceDiagram
    participant C as Caller
    participant S as BankingService
    participant L as LockManager
    participant R as AccountRepository
    participant G as LedgerRepository

    C->>S: withdraw(accountId, amount)
    S->>S: requirePositiveAmount(amount)
    S->>R: findById(accountId)
    S->>L: acquireOrdered([accountId])
    S->>R: findById(accountId)
    S->>S: requireSameCurrency(amount, account)
    alt balance >= amount
        S->>S: account = account.debit(amount.minor)
        S->>R: save(account)
        S->>G: append(Transaction:<br/>debit account / credit CASH_CONTRA)
        S-->>C: Transaction
    else insufficient
        Note over S: throw InsufficientFundsException<br/>(balance unchanged, nothing appended)
    end
    S->>L: releaseAll()
```

## Transfer — same currency

```mermaid
sequenceDiagram
    participant C as Caller
    participant S as BankingService
    participant L as LockManager
    participant R as AccountRepository
    participant G as LedgerRepository

    C->>S: transfer(from, to, amount)
    S->>S: amount > 0, from != to
    S->>R: findById(from), findById(to)
    S->>L: acquireOrdered([from, to])
    Note over L: locks acquired in AccountId order → deadlock-free
    S->>R: findById(from), findById(to)
    S->>S: requireSameCurrency(amount, source)
    S->>S: check source.balance >= amount
    S->>S: source.debit / destination.credit (same minor units)
    S->>R: save(source), save(destination)
    S->>G: append(Transaction:<br/>debit source / credit destination)
    S->>L: releaseAll()
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
    participant F as ExchangeRateProvider
    participant L as LockManager
    participant R as AccountRepository
    participant G as LedgerRepository

    C->>S: transfer(from, to, amount)
    S->>L: acquireOrdered([from, to])
    S->>R: findById(from), findById(to)
    S->>S: requireSameCurrency(amount, source)
    S->>F: rate(source.currency, destination.currency)
    alt rate missing
        Note over S: throw FxRateUnavailableException (nothing mutated)
    end
    S->>S: amount.convert(dest.currency, rate) → destAmount (HALF_UP)
    S->>S: source.debit(sourceMinor), destination.credit(destMinor)
    S->>R: save(source), save(destination)
    S->>G: append(Transaction:<br/>debit source + credit FX_CONTRA (source ccy)<br/>debit FX_CONTRA + credit destination (dest ccy))
    S->>L: releaseAll()
    S-->>C: Transaction
```

## Account creation & balance

```mermaid
sequenceDiagram
    participant C as Caller
    participant S as BankingService
    participant R as AccountRepository
    participant G as LedgerRepository

    C->>S: createAccount(owner, currency, initialDeposit)
    S->>S: initialDeposit.currency == currency
    S->>S: AccountFactory.newAccount(...)
    S->>R: save(account)
    opt initialDeposit > 0
        S->>G: append(Transaction type=CREATE:<br/>credit account / debit CASH_CONTRA)
    end
    S-->>C: Account
    Note over C,S: getBalance(id): acquireOrdered([id]) → findById → balance() → release
```

A zero opening deposit creates the account but posts no ledger transaction.

## Concurrency: ordered locking

The `LockManager` always acquires account locks in `AccountId.compareTo` order. Two transfers
running in opposite directions between the same accounts therefore request the **same** lock
sequence — there is no cyclic wait, so the system cannot deadlock.

```mermaid
flowchart LR
    subgraph T1["Thread 1: transfer A → B"]
        T1A["order [A,B]"] --> T1L1["lock A"]
        T1L1 --> T1L2["lock B"]
        T1L2 --> T1W["work"]
    end
    subgraph T2["Thread 2: transfer B → A"]
        T2A["order [A,B]"] --> T2L1["lock A"]
        T2L1 --> T2L2["lock B"]
        T2L2 --> T2W["work"]
    end

    T1L1 -.->|blocks T2L1| T2L1
```

Self-transfers are de-duplicated before locking (the id set has one element), so a single lock is
taken. Locks are released in reverse acquisition order in a `finally` block. This same
"canonical lock order" maps directly onto `SELECT … FOR UPDATE` ordering in a future database
adapter.

## Idempotency: at-most-once execution

Mutating operations accept an optional `IdempotencyKey`. The store claims the key atomically
(`putIfAbsent`); the first caller executes and stores the result (success **or** the thrown
business exception); concurrent or replayed duplicates with the same key wait for and return that
single result. Reusing a key with **different** parameters is rejected.

```mermaid
flowchart TD
    Start([op with key]) --> Claim["store.putIfAbsent(key, newRecord)"]
    Claim --> Owner?{"owner?"}
    Owner? -->|"yes (new key)"| Run["execute operation"]
    Run --> Complete["complete future with result/exception"]
    Complete --> Done([return result])
    Owner? -->|"no (key exists)"| Fp{"fingerprint<br/>matches?"}
    Fp -->|"no"| Conflict(["throw IdempotencyConflictException"])
    Fp -->|"yes"| Join["future.join() — wait for the single execution"]
    Join --> Replay([return stored result / rethrow stored exception])
```

Operations called without a key bypass the store entirely and always execute.
