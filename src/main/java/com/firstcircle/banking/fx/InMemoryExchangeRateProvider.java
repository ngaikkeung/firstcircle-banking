package com.firstcircle.banking.fx;

import com.firstcircle.banking.exceptions.FxRateUnavailableException;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Simple in-memory {@link ExchangeRateProvider} backed by an explicit rate table. Intended for
 * tests and demos; production would use a live feed. Rates are directional ({@code from->to}
 * is independent of {@code to->from}).
 */
public final class InMemoryExchangeRateProvider implements ExchangeRateProvider {

    private final Map<CurrencyPair, BigDecimal> rates;

    private InMemoryExchangeRateProvider(Map<CurrencyPair, BigDecimal> rates) {
        this.rates = Map.copyOf(rates);
    }

    public static InMemoryExchangeRateProvider empty() {
        return new InMemoryExchangeRateProvider(Map.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public BigDecimal rate(Currency from, Currency to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.equals(to)) {
            return BigDecimal.ONE;
        }
        BigDecimal rate = rates.get(new CurrencyPair(from, to));
        if (rate == null) {
            throw new FxRateUnavailableException(from, to);
        }
        return rate;
    }

    /** A directional currency pair key. */
    private record CurrencyPair(Currency from, Currency to) {
    }

    public static final class Builder {
        private final Map<CurrencyPair, BigDecimal> rates = new HashMap<>();

        public Builder rate(String fromCode, String toCode, String rate) {
            return rate(Currency.getInstance(fromCode), Currency.getInstance(toCode), new BigDecimal(rate));
        }

        public Builder rate(Currency from, Currency to, BigDecimal rate) {
            rates.put(new CurrencyPair(from, to), rate);
            return this;
        }

        public InMemoryExchangeRateProvider build() {
            return new InMemoryExchangeRateProvider(rates);
        }
    }
}
