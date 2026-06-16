package com.firstcircle.banking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstcircle.banking.TestFixtures;
import com.firstcircle.banking.exceptions.CurrencyMismatchException;
import com.firstcircle.banking.exceptions.NegativeAmountException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void ofMinorAndOfProduceEquivalentAmounts() {
        assertThat(Money.ofMinor(12345, TestFixtures.HKD)).hasToString("HKD 123.45");
        assertThat(Money.of(12, 34, TestFixtures.USD)).isEqualTo(Money.ofMinor(1234, TestFixtures.USD));
        assertThat(Money.zero(TestFixtures.JPY)).isEqualTo(Money.ofMinor(0, TestFixtures.JPY));
    }

    @Test
    void negativeAmountCannotBeConstructed() {
        assertThatThrownBy(() -> Money.ofMinor(-1, TestFixtures.HKD))
                .isInstanceOf(NegativeAmountException.class);
    }

    @Test
    void plusAndMinusPreserveCurrency() {
        Money base = Money.ofMinor(10000, TestFixtures.HKD); // HKD 100.00
        assertThat(base.plus(Money.ofMinor(500, TestFixtures.HKD))).isEqualTo(Money.ofMinor(10500, TestFixtures.HKD));
        assertThat(base.minus(Money.ofMinor(250, TestFixtures.HKD))).isEqualTo(Money.ofMinor(9750, TestFixtures.HKD));
    }

    @Test
    void arithmeticAcrossCurrenciesIsRejected() {
        Money hkd = Money.ofMinor(1000, TestFixtures.HKD);
        assertThatThrownBy(() -> hkd.plus(Money.ofMinor(1000, TestFixtures.USD)))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> hkd.minus(Money.ofMinor(1000, TestFixtures.USD)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void minusCannotGoNegative() {
        Money hkd = Money.ofMinor(100, TestFixtures.HKD);
        assertThatThrownBy(() -> hkd.minus(Money.ofMinor(101, TestFixtures.HKD)))
                .isInstanceOf(NegativeAmountException.class);
    }

    @Test
    void convertSameCurrencyReturnsSameValue() {
        Money hkd = Money.ofMinor(12345, TestFixtures.HKD);
        assertThat(hkd.convert(TestFixtures.HKD, new BigDecimal("2"))).isSameAs(hkd);
    }

    @Test
    void convertAppliesHalfUpRoundingToTargetMinorUnits() {
        // HKD 123.45 @ 0.0175 = 2.160375 USD -> 2.16 USD (216 cents), HALF_UP.
        Money hkd = Money.ofMinor(12345, TestFixtures.HKD);
        Money usd = hkd.convert(TestFixtures.USD, new BigDecimal("0.0175"));
        assertThat(usd.minor()).isEqualTo(216L);
        assertThat(usd.currency()).isEqualTo(TestFixtures.USD);
    }

    @Test
    void convertToZeroDecimalCurrencyHasNoFraction() {
        // USD 1.00 @ 150 = 150 JPY (0 fraction digits).
        Money usd = Money.ofMinor(100, TestFixtures.USD);
        Money jpy = usd.convert(TestFixtures.JPY, new BigDecimal("150"));
        assertThat(jpy.minor()).isEqualTo(150L);
        assertThat(jpy).hasToString("JPY 150");
    }

    @Test
    void halfUpRoundsHalfAwayFromZero() {
        // 1.005 USD-ish: HKD 100.00 @ 0.01005 = 1.005 USD -> HALF_UP -> 1.01 USD (101 cents).
        Money hkd = Money.ofMinor(10000, TestFixtures.HKD);
        Money usd = hkd.convert(TestFixtures.USD, new BigDecimal("0.01005"));
        assertThat(usd.minor()).isEqualTo(101L);
    }

    @Test
    void predicatesAndEquality() {
        assertThat(Money.zero(TestFixtures.HKD).isZero()).isTrue();
        assertThat(Money.zero(TestFixtures.HKD).isPositive()).isFalse();
        assertThat(Money.ofMinor(1, TestFixtures.HKD).isPositive()).isTrue();
        assertThat(Money.ofMinor(1, TestFixtures.HKD)).isNotEqualTo(Money.ofMinor(1, TestFixtures.USD));
        assertThat(Money.ofMinor(5, TestFixtures.HKD)).isEqualTo(Money.ofMinor(5, TestFixtures.HKD));
    }
}
