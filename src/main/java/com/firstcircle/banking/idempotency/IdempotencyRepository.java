package com.firstcircle.banking.idempotency;

import com.firstcircle.banking.db.UniqueViolationException;
import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for at-most-once execution via a {@code UNIQUE} request key, claimed inside the caller's
 * transaction.
 *
 * <p>The pattern (driven by {@code BankingService}):
 * <ol>
 *   <li>{@link #findByKey} — if present and the fingerprint matches, return the stored result
 *       (replay); if the fingerprint differs, the caller rejects it as a conflict.</li>
 *   <li>Otherwise {@link #claim} the key (reserve it) <em>before</em> doing the work. A
 *       {@link UniqueViolationException} means a concurrent request won the race — roll back and
 *       load the winner's result instead.</li>
 * </ol>
 * Claiming inside the transaction means a rolled-back operation frees its key (failures are not
 * pinned), and the claim commits atomically with the balance change.
 */
public interface IdempotencyRepository {

    Optional<IdempotencyRecord> findByKey(IdempotencyKey key, Connection connection);

    /**
     * Insert the claim. Throws {@link UniqueViolationException} if {@code key} already exists
     * (committed by another transaction).
     */
    void claim(IdempotencyKey key, String fingerprint, IdempotencyResultKind resultKind,
               UUID resultRef, Connection connection);

    /** Number of stored claims — for tests asserting that non-keyed ops bypass idempotency. */
    long count(Connection connection);
}
