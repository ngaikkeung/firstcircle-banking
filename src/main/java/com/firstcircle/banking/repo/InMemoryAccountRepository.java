package com.firstcircle.banking.repo;

import com.firstcircle.banking.domain.Account;
import com.firstcircle.banking.domain.AccountId;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Thread-safe in-memory {@link AccountRepository} backed by a {@link ConcurrentHashMap}. */
public final class InMemoryAccountRepository implements AccountRepository {

    private final ConcurrentMap<AccountId, Account> accounts = new ConcurrentHashMap<>();

    @Override
    public Account save(Account account) {
        accounts.put(account.id(), account);
        return account;
    }

    @Override
    public Optional<Account> findById(AccountId id) {
        return Optional.ofNullable(accounts.get(id));
    }

    @Override
    public boolean existsById(AccountId id) {
        return accounts.containsKey(id);
    }

    @Override
    public Collection<Account> findAll() {
        return accounts.values(); // live view; callers should not mutate
    }
}
