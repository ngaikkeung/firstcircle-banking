# Ledger design

The ledger is **double-entry**: every posting is a set of signed entries that must net to zero
**per currency**. This makes money conserving and auditable — within a transaction, nothing is
created or destroyed; for every currency, what leaves one account enters another (or a contra
account).

## Signed entries

Each `LedgerEntry` carries:

- `account` — the `AccountId` (or a contra account) it posts to
- `currency` — the currency of the amount
- `signedAmount` — minor units **with sign**: **positive = CREDIT** (adds to balance),
  **negative = DEBIT** (subtracts)
- `type` — `CREDIT` / `DEBIT` (mirrors the sign; kept for readability/audit)

Entries are built only via `LedgerEntry.credit(...)` / `LedgerEntry.debit(...)`, which take a
positive magnitude and derive the sign — this removes the classic double-entry bug of a
mismatched sign vs. type.

## The per-currency zero-sum invariant

`Transaction.create` groups entries by currency and asserts each group sums to `0`. An unbalanced
transaction therefore **cannot be constructed** — the invariant is enforced at the only entry
point, not left to caller discipline.

```
For each currency c in a transaction:   Σ signedAmount(entries where currency = c) == 0
```

## Contra accounts

Two fixed system identities (`ContraAccountIds`) act as the counterparty so real accounts always
have something balanced against:

| Contra | Role |
|---|---|
| `CASH_CONTRA` | The vault. Deposits credit the customer and debit `CASH_CONTRA`; withdrawals do the reverse. |
| `FX_CONTRA` | The FX desk. Cross-currency transfers post both legs against `FX_CONTRA`; it absorbs the FX position and any rounding residue. |

Contra accounts appear only in ledger entries — they are not customer `Account`s and are not
queried via `getBalance`.

## Posting patterns

### Deposit — funds enter the system
| Account | Currency | Signed | Type |
|---|---|---|---|
| customer | HKD | +amount | CREDIT |
| CASH_CONTRA | HKD | −amount | DEBIT |

### Withdrawal — funds leave the system
| Account | Currency | Signed | Type |
|---|---|---|---|
| customer | HKD | −amount | DEBIT |
| CASH_CONTRA | HKD | +amount | CREDIT |

### Transfer — same currency
| Account | Currency | Signed | Type |
|---|---|---|---|
| source | HKD | −amount | DEBIT |
| destination | HKD | +amount | CREDIT |

## Worked example — cross-currency transfer with rounding residue

Transfer **HKD 10.55** (1055 minor) from a HKD account to a USD account at rate
**HKD→USD = 0.128**.

`10.55 × 0.128` in USD major units = `1.3504`, which rounds `HALF_UP` to **USD 1.35**
(135 minor). The HKD leg and the USD leg each balance independently; `FX_CONTRA` absorbs the
rounding residue as FX P&L.

| Account | Currency | Signed | Type | Minor |
|---|---|---|---|---|
| source | HKD | −1055 | DEBIT |  |
| FX_CONTRA | HKD | +1055 | CREDIT |  |
| FX_CONTRA | USD | −135 | DEBIT |  |
| destination | USD | +135 | CREDIT |  |

Check:
- HKD leg: `−1055 + 1055 = 0` ✓
- USD leg: `−135 + 135 = 0` ✓
- Customer effect: source `−HKD 10.55`, destination `+USD 1.35` ✓
- FX residue: `FX_CONTRA` is long `HKD 1055` and short `USD 135`; the `0.0004 USD` rounding
  gain/loss lives here. ✓

> Same math, exact (no residue): HKD 1000.00 @ 0.128 = USD 128.00 → source `−100000 HKD`,
> destination `+12800 USD`, contra `+100000 HKD / −12800 USD`.

## Balance derivation

A customer's balance is the sum of their signed entries across the whole ledger:

```
balance(account, currency) = Σ signedAmount(LedgerEntry where account = this and currency = c)
```

`Account.balanceMinor` is a cached value kept in lock-step with this sum; `BankingServiceBalanceTest`
asserts the cache equals the ledger sum after a sequence of operations.

## Integrity invariants

These hold at all times and are asserted by the test suite (including after concurrency stress):

1. Every `Transaction` nets to zero per currency.
2. No `Account.balanceMinor` is ever negative.
3. The sum of all real (customer) account balances is invariant across same-currency transfers
   (closed-system conservation).
4. Contra accounts (`CASH_CONTRA`, `FX_CONTRA`) absorb all counterparties, so the **system-wide**
   per-currency sum (customers + contras) is always zero.
