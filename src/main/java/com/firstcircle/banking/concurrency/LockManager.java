package com.firstcircle.banking.concurrency;

import com.firstcircle.banking.domain.AccountId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns one {@link ReentrantLock} per {@link AccountId} and provides deadlock-free acquisition
 * of multiple account locks in a single, consistent global order.
 *
 * <h3>Why this design</h3>
 * <ul>
 *   <li><b>Deadlock-free:</b> locks are always acquired in {@link AccountId#compareTo} order.
 *     Since every thread acquires the same set of locks in the same order, there is no cyclic
 *     wait.</li>
 *   <li><b>Self-transfer safe:</b> the id list is de-duplicated before locking, so a transfer
 *     from an account to itself locks exactly once.</li>
 *   <li><b>Granularity:</b> per-account locks let independent accounts operate in parallel,
 *     unlike a single global lock.</li>
 *   <li><b>Real-DB ready:</b> the "lock in canonical id order" rule maps directly onto
 *     {@code SELECT ... FOR UPDATE} ordering when a real database is introduced.</li>
 * </ul>
 *
 * <p>Locks are non-fair (better throughput; fairness is rarely needed and documented as a
 * future option).
 */
public final class LockManager {

    private final ConcurrentMap<AccountId, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * Acquire the locks for the given accounts in canonical order and return them in
     * acquisition order. The caller MUST pass the returned list to {@link #releaseAll} in a
     * {@code finally} block.
     */
    public List<ReentrantLock> acquireOrdered(Collection<AccountId> ids) {
        // TreeSet both de-duplicates (handles self-transfer) and sorts via AccountId.compareTo.
        TreeSet<AccountId> ordered = new TreeSet<>(ids);
        List<ReentrantLock> held = new ArrayList<>(ordered.size());
        for (AccountId id : ordered) {
            ReentrantLock lock = lockFor(id);
            lock.lock();
            held.add(lock);
        }
        return held;
    }

    /** Release previously acquired locks in the reverse of acquisition order. */
    public void releaseAll(List<ReentrantLock> held) {
        for (int i = held.size() - 1; i >= 0; i--) {
            held.get(i).unlock();
        }
    }

    public ReentrantLock lockFor(AccountId id) {
        return locks.computeIfAbsent(id, ignored -> new ReentrantLock(false));
    }
}
