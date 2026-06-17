package com.firstcircle.banking.idempotency;

import java.util.UUID;

/**
 * A stored idempotency claim: the request's fingerprint (to detect key reuse with different params),
 * the kind of result it produced, and the id of that result (to load it on replay).
 */
public record IdempotencyRecord(String fingerprint, IdempotencyResultKind resultKind, UUID resultRef) {
}
