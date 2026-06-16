package com.firstcircle.banking.domain;

import com.firstcircle.banking.exceptions.CurrencyMismatchException;
import com.firstcircle.banking.exceptions.NegativeAmountException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * An amount of money in a specific currency, stored as signed {@code long} minor units
 * (e.g. cents for HKD and USD, whole yen for JPY).
 *
 * <p>Minor-unit {@code long} is exact (no floating point), fast, and trivially thread-safe
 * to read. {@link BigDecimal} is used only at the foreign-exchange boundary in
 * {@link #convert(Currency, BigDecimal)}, where fractional values and an explicit rounding
 * policy are unavoidable.
 *
 * <p>Immutable: every arithmetic operation returns a new {@link Money}.
 */
@Getter
@EqualsAndHashCode
public final class Money {

    private final long minor;
    private final Currency currency;

    private Money(long minor, Currency currency) {
        if (minor < 0) {
            // A Money value is always non-negative; negative magnitudes are rejected at
            // construction. "Negative money" is never a legal balance or amount.
            throw new NegativeAmountException("Money amount must be non-negative: " + minor + " " + currency);
        }
        this.minor = minor;
        this.currency = Objects.requireNonNull(currency, "currency");
    }

    /** Money expressed directly in minor units (e.g. {@code ofMinor(12345, HKD)} == HKD 123.45). */
    public static Money ofMinor(long minor, Currency currency) {
        return new Money(minor, currency);
    }

    /** Convenience: {@code of(12, 34, USD)} == USD 12.34. */
    public static Money of(long majorUnits, long minorUnits, Currency currency) {
        int fractionDigits = currency.getDefaultFractionDigits();
        long asMinor = majorUnits * pow10(fractionDigits) + minorUnits;
        return new Money(asMinor, currency);
    }

    public static Money zero(Currency currency) {
        return new Money(0L, currency);
    }

    public boolean isZero() {
        return minor == 0L;
    }

    public boolean isPositive() {
        return minor > 0L;
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(this.minor + other.minor, currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(this.minor - other.minor, currency); // throws if result negative
    }

    /**
     * Convert this amount to {@code target} currency at the given spot rate.
     *
     * <p>{@code rate} means "1 unit of {@code this.currency} == {@code rate} units of
     * {@code target}". The result is rounded to the target currency's minor units using
     * {@link RoundingMode#HALF_UP}, the standard for monetary conversion. Any rounding
     * residue is absorbed by the FX contra account in the ledger (see {@code BankingService}).
     *
     * @throws ArithmeticException if the converted amount overflows {@code long}
     */
    public Money convert(Currency target, BigDecimal rate) {
        Objects.requireNonNull(target, "target currency");
        Objects.requireNonNull(rate, "rate");
        if (currency.equals(target)) {
            return this;
        }
        int sourceDigits = currency.getDefaultFractionDigits();
        int targetDigits = target.getDefaultFractionDigits();

        // major units of source -> multiplied by rate -> major units of target -> minor units of target
        BigDecimal sourceMajor = BigDecimal.valueOf(minor).movePointLeft(sourceDigits);
        BigDecimal targetMajor = sourceMajor.multiply(rate);
        BigDecimal targetMinor = targetMajor.movePointRight(targetDigits)
                .setScale(0, RoundingMode.HALF_UP);
        long result = targetMinor.longValueExact(); // overflow guard
        return new Money(result, target);
    }

    private void requireSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new CurrencyMismatchException(this.currency, other.currency);
        }
    }

    private static long pow10(int n) {
        long r = 1L;
        for (int i = 0; i < n; i++) {
            r *= 10L;
        }
        return r;
    }

    @Override
    public String toString() {
        int digits = currency.getDefaultFractionDigits();
        BigDecimal major = BigDecimal.valueOf(minor).movePointLeft(digits);
        return currency.getCurrencyCode() + " " + major.toPlainString();
    }
}
