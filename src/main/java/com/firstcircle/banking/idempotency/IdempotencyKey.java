package com.firstcircle.banking.idempotency;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * A client-supplied token that makes a mutating request idempotent: if the same key is seen
 * again (a retry or a duplicate submission), the originally-recorded result is returned
 * instead of executing the operation a second time.
 *
 * <p>Immutable value object. A key must be a non-blank string.
 */
@Getter
@EqualsAndHashCode
@ToString
public final class IdempotencyKey {

    private final String value;

    public IdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("idempotency key must not be blank");
        }
        this.value = value;
    }

    public static IdempotencyKey of(String value) {
        return new IdempotencyKey(value);
    }
}
