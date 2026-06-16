package com.firstcircle.banking.idempotency;

import java.util.function.Supplier;

/**
 * Port that provides at-most-once execution and replay protection for mutating operations.
 *
 * <p>{@code fingerprint} is a canonical description of the request parameters. When a key is
 * reused with a different fingerprint, the store rejects it (the caller is misusing the key).
 *
 * @param <T> the result type of the guarded operation
 */
public interface IdempotencyStore {

    /**
     * Execute {@code op} exactly once for {@code key}.
     * <ul>
     *   <li>If the key is new, run {@code op} and store its result (or its thrown exception).</li>
     *   <li>If the key already exists with the same fingerprint, return the stored result
     *       (or rethrow the stored exception) without re-running {@code op}.</li>
     *   <li>If the key already exists with a different fingerprint, throw
     *       {@code IdempotencyConflictException}.</li>
     * </ul>
     */
    <T> T executeOnce(IdempotencyKey key, String fingerprint, Supplier<T> op);
}
