package com.firstcircle.banking.domain;

import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

/**
 * Immutable identity for an account. {@link Comparable} so that {@code SELECT … FOR UPDATE}
 * row locks can be acquired in a single canonical order, which is what makes
 * multi-account transfers deadlock-free.
 */
@Getter
@EqualsAndHashCode
@ToString
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class AccountId implements Comparable<AccountId> {

    @NonNull
    private final UUID value;

    public static AccountId of(UUID value) {
        return new AccountId(value);
    }

    /** Parse a previously stringified id (e.g. loaded from a future store). */
    public static AccountId parse(String value) {
        return new AccountId(UUID.fromString(value));
    }

    public static AccountId random() {
        return new AccountId(UUID.randomUUID());
    }

    @Override
    public int compareTo(AccountId other) {
        return this.value.compareTo(other.value);
    }
}
