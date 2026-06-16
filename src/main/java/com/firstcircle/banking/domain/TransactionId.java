package com.firstcircle.banking.domain;

import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

/** Immutable identity for a ledger transaction. */
@Getter
@EqualsAndHashCode
@ToString
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class TransactionId {

    @NonNull
    private final UUID value;

    public static TransactionId of(UUID value) {
        return new TransactionId(value);
    }

    public static TransactionId random() {
        return new TransactionId(UUID.randomUUID());
    }
}
