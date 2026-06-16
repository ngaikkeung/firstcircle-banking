package com.firstcircle.banking.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstcircle.banking.TestFixtures;
import com.firstcircle.banking.exceptions.FxRateUnavailableException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FxConversionTest {

    private final InMemoryExchangeRateProvider provider = InMemoryExchangeRateProvider.builder()
            .rate("HKD", "USD", "0.128")
            .build();

    @Test
    void sameCurrencyIsFastPathAtOne() {
        assertThat(provider.rate(TestFixtures.HKD, TestFixtures.HKD)).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(provider.rate(TestFixtures.USD, TestFixtures.USD)).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void configuredRateIsReturned() {
        assertThat(provider.rate(TestFixtures.HKD, TestFixtures.USD)).isEqualByComparingTo(new BigDecimal("0.128"));
    }

    @Test
    void missingRateThrows() {
        assertThatThrownBy(() -> provider.rate(TestFixtures.USD, TestFixtures.HKD))
                .isInstanceOf(FxRateUnavailableException.class);
    }

    @Test
    void emptyProviderOnlyKnowsSameCurrency() {
        ExchangeRateProvider empty = InMemoryExchangeRateProvider.empty();
        assertThat(empty.rate(TestFixtures.EUR, TestFixtures.EUR)).isEqualByComparingTo(BigDecimal.ONE);
        assertThatThrownBy(() -> empty.rate(TestFixtures.EUR, TestFixtures.USD))
                .isInstanceOf(FxRateUnavailableException.class);
    }
}
