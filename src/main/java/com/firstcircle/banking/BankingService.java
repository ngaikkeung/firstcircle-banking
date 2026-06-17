package com.firstcircle.banking;

import com.firstcircle.banking.domain.Account;
import com.firstcircle.banking.domain.AccountId;
import com.firstcircle.banking.domain.Money;
import com.firstcircle.banking.domain.Transaction;
import com.firstcircle.banking.exceptions.AccountNotFoundException;
import com.firstcircle.banking.exceptions.CurrencyMismatchException;
import com.firstcircle.banking.exceptions.InsufficientFundsException;
import com.firstcircle.banking.exceptions.NegativeAmountException;
import com.firstcircle.banking.exceptions.SameAccountTransferException;
import com.firstcircle.banking.idempotency.IdempotencyKey;

import java.util.Currency;
import java.util.List;

/**
 * Public contract for basic banking operations: account creation, deposit, withdrawal,
 * transfer, and balance enquiry.
 *
 * <p>All mutating operations are atomic: either the whole operation (balance changes + ledger
 * posting) commits, or none of it is visible. No overdraft is ever permitted.
 *
 * <p>Every mutating method accepts an optional {@link IdempotencyKey}; when the same key is
 * presented twice, the second call returns the original result without re-executing.
 */
public interface BankingService {

    // ---------------------------------------------------------------- create

    /**
     * Create a new account with an initial deposit.
     *
     * @throws CurrencyMismatchException if the deposit currency differs from the account currency
     * @throws NegativeAmountException   if the initial deposit is negative (cannot be constructed)
     */
    Account createAccount(String ownerName, Currency currency, Money initialDeposit);

    /**
     * Idempotent variant of {@link #createAccount(String, Currency, Money)}.
     */
    Account createAccount(String ownerName, Currency currency, Money initialDeposit, IdempotencyKey key);

    // ---------------------------------------------------------------- deposit

    /**
     * Deposit money into an account.
     *
     * @throws AccountNotFoundException  if no account exists with the given id
     * @throws CurrencyMismatchException if the deposit currency differs from the account currency
     * @throws NegativeAmountException   if the amount is not strictly positive
     */
    Transaction deposit(AccountId id, Money amount);

    /**
     * Idempotent variant of {@link #deposit(AccountId, Money)}.
     */
    Transaction deposit(AccountId id, Money amount, IdempotencyKey key);

    // ---------------------------------------------------------------- withdraw

    /**
     * Withdraw money from an account. Overdrafts are not permitted.
     *
     * @throws AccountNotFoundException   if no account exists with the given id
     * @throws InsufficientFundsException if the balance is less than the withdrawal amount
     * @throws CurrencyMismatchException  if the amount currency differs from the account currency
     * @throws NegativeAmountException    if the amount is not strictly positive
     */
    Transaction withdraw(AccountId id, Money amount);

    /**
     * Idempotent variant of {@link #withdraw(AccountId, Money)}.
     */
    Transaction withdraw(AccountId id, Money amount, IdempotencyKey key);

    // ---------------------------------------------------------------- transfer

    /**
     * Transfer funds between two accounts. Supports same-currency and cross-currency transfers.
     *
     * @throws AccountNotFoundException     if either account does not exist
     * @throws SameAccountTransferException if {@code from} and {@code to} are the same account
     * @throws InsufficientFundsException   if the source balance is less than the transfer amount
     * @throws CurrencyMismatchException    if the amount currency differs from the source account currency
     * @throws NegativeAmountException      if the amount is not strictly positive
     */
    Transaction transfer(AccountId from, AccountId to, Money amount);

    /**
     * Idempotent variant of {@link #transfer(AccountId, AccountId, Money)}.
     */
    Transaction transfer(AccountId from, AccountId to, Money amount, IdempotencyKey key);

    // ---------------------------------------------------------------- balance

    /**
     * Return the current balance of the given account.
     *
     * @throws AccountNotFoundException if no account exists with the given id
     */
    Money getBalance(AccountId id);

    // ---------------------------------------------------------------- ledger

    /**
     * Every posted transaction, in sequence order. Exposed for tests and auditing.
     */
    List<Transaction> ledger();
}
