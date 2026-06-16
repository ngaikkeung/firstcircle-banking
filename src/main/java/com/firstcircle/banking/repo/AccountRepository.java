package com.firstcircle.banking.repo;

import com.firstcircle.banking.domain.Account;
import com.firstcircle.banking.domain.AccountId;
import java.util.Collection;
import java.util.Optional;

/**
 * Port for account storage.
 *
 * <p><b>Concurrency contract:</b> implementations are individually thread-safe, but they do
 * NOT provide multi-key atomicity. Acquiring locks for multi-account operations is the
 * responsibility of {@code BankingService} via {@code LockManager}. A future database adapter
 * would back these calls with {@code SELECT ... FOR UPDATE} while preserving that contract.
 */
public interface AccountRepository {

    Account save(Account account);

    Optional<Account> findById(AccountId id);

    boolean existsById(AccountId id);

    Collection<Account> findAll();
}
