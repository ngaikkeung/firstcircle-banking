package com.firstcircle.banking.idempotency;

import com.firstcircle.banking.exceptions.IdempotencyConflictException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * In-memory {@link IdempotencyStore}. Claims a key atomically via {@code putIfAbsent} so that
 * concurrent duplicates of the same key execute the operation exactly once; subsequent callers
 * (retries, racing duplicates) wait for and return that single result.
 *
 * <p>Both successful results and business exceptions are cached, so a replay of a failed
 * request (e.g. insufficient funds) replays the same failure — true idempotency rather than
 * "only-successful" idempotency. The store is in-memory and session-scoped; production would
 * use a shared, durable store (e.g. Redis or a DB row per key).
 */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    private final ConcurrentMap<IdempotencyKey, Record> records = new ConcurrentHashMap<>();

    @Override
    public <T> T executeOnce(IdempotencyKey key, String fingerprint, Supplier<T> op) {
        Record candidate = new Record(fingerprint, new CompletableFuture<>());
        Record winner = records.putIfAbsent(key, candidate);
        boolean iAmOwner = (winner == null);
        Record record = iAmOwner ? candidate : winner;

        if (!record.fingerprint().equals(fingerprint)) {
            throw new IdempotencyConflictException(key, record.fingerprint(), fingerprint);
        }

        if (iAmOwner) {
            try {
                T result = op.get();
                record.future().complete(result);
                return result;
            } catch (RuntimeException ex) {
                record.future().completeExceptionally(ex);
                throw ex;
            }
        }

        // Not the owner: a concurrent/replayed duplicate. Wait for the single execution.
        try {
            @SuppressWarnings("unchecked")
            T result = (T) record.future().join();
            return result;
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw ce;
        }
    }

    /** For tests: number of distinct keys recorded. */
    public int size() {
        return records.size();
    }

    @Getter
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class Record {
        private final String fingerprint;
        private final CompletableFuture<Object> future;
    }
}
