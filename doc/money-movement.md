# Money movement

How money enters, leaves, and moves within the system, and the conservation rules that follow
from the double-entry ledger. Arrows show the direction positive money flows; the contra account
is always the balancing counterparty.

## Deposit — money enters

```mermaid
flowchart LR
    VAULT["CASH_CONTRA<br/>(outside the system)"] -- "+amount credited" --> ACC["customer account"]
    ACC -- "(debit on CASH_CONTRA)" --> VAULT
```

A deposit increases a customer balance and decreases `CASH_CONTRA` by the same amount. Net new
money in the customer set = `+amount`.

## Withdrawal — money leaves

```mermaid
flowchart LR
    ACC["customer account"] -- "+amount debited" --> VAULT["CASH_CONTRA<br/>(outside the system)"]
    VAULT -- "(credit on CASH_CONTRA)" --> ACC
```

A withdrawal decreases the customer balance and increases `CASH_CONTRA`. The customer set loses
`amount`. Overdraft is impossible: sufficiency is checked **under the account lock** before the
debit.

## Transfer — same currency (no money created/destroyed)

```mermaid
flowchart LR
    SRC["source account"] -- "amount" --> DST["destination account"]
```

Same-currency transfer moves `amount` directly. The sum of the two (and of all) real-account
balances is unchanged — money is merely relocated.

## Transfer — cross currency (money routed via the FX desk)

```mermaid
flowchart LR
    SRC["source<br/>(source ccy)"] -- "sourceAmount" --> FX["FX_CONTRA"]
    FX -- "destAmount<br/>(rate, HALF_UP)" --> DST["destination<br/>(dest ccy)"]
```

The source currency debits the source and credits `FX_CONTRA`; the destination currency debits
`FX_CONTRA` and credits the destination. `FX_CONTRA` ends up long the source currency and short
the destination currency — i.e. it holds the FX position.

### FX rounding residue

Conversion uses `BigDecimal` with `RoundingMode.HALF_UP` to the destination's minor units. When
the converted amount is not exact, the rounding difference stays on `FX_CONTRA` as FX P&L — it is
never silently dropped or added to a customer.

Example: HKD 10.55 @ 0.128 → exact USD 1.3504 → rounded **USD 1.35**. The `0.0004 USD` is the
`FX_CONTRA` P&L. (Full entry table in [ledger.md](ledger.md).)

## Conservation laws

These are direct consequences of per-currency double-entry and are verified by the tests.

```mermaid
flowchart TD
    SYSTEM["All accounts: customers + CASH_CONTRA + FX_CONTRA"]

    subgraph Law1["1. System-wide per-currency sum is always 0"]
        L1["Σ all signedAmount(currency c) = 0<br/>for every c — because every transaction balances per c"]
    end
    subgraph Law2["2. Transfers conserve the customer set"]
        L2["Σ customer balances is invariant<br/>under same-currency transfers"]
    end
    subgraph Law3["3. Deposits/withdrawals move money vs. CASH_CONTRA"]
        L3["deposits raise the customer sum;<br/>withdrawals lower it — exactly mirrored on CASH_CONTRA"]
    end

    SYSTEM --> Law1
    SYSTEM --> Law2
    SYSTEM --> Law3
```

- **Closed-system conservation** (used by `ConcurrencyStressTest`): with a set of same-currency
  accounts performing only transfers among themselves, `Σ balances` is constant, even under heavy
  concurrency. Failed transfers (insufficient funds, self-transfer) move nothing, so conservation
  still holds.
- **No overdraft, no lost updates**: because each account's balance is read-then-written under
  that account's lock, concurrent operations never lose an update and never drive a balance below
  zero.

## Putting it together — a session

A small end-to-end scenario and how money moves (this mirrors `Demo.main()`):

```mermaid
sequenceDiagram
    participant D as Demo
    participant B as BankingService
    participant CC as CASH_CONTRA
    participant FX as FX_CONTRA

    D->>B: create HKD account (HKD 100,000)
    Note over B,CC: +HKD 100,000 customer / −HKD 100,000 CASH_CONTRA
    D->>B: deposit USD 5,000 to USD account
    Note over B,CC: +USD 5,000 customer / −USD 5,000 CASH_CONTRA
    D->>B: transfer HKD 10,000 → USD account
    Note over B,FX: source −HKD 10,000, dest +USD 1,280.00<br/>FX_CONTRA +HKD 10,000 / −USD 1,280.00
    D->>B: withdraw HKD 1,500
    Note over B,CC: customer −HKD 1,500, CASH_CONTRA +HKD 1,500
    D->>B: deposit HKD 2,000 (idempotency key)
    D->>B: deposit HKD 2,000 (same key)
    Note over B: executed once, customer +HKD 2,000 only
```

Every customer balance change above is matched by an equal, opposite entry on a contra account,
so the system-wide per-currency sum remains zero throughout.
