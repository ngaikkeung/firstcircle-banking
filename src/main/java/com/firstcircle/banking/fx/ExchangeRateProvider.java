package com.firstcircle.banking.fx;

import com.firstcircle.banking.exceptions.FxRateUnavailableException;
import java.math.BigDecimal;
import java.util.Currency;

/**
 * Port for obtaining foreign-exchange rates. Swappable so production can plug in a live
 * rate feed, a cached provider, etc.
 *
 * <p>The returned rate means "1 unit of {@code from} == {@code rate} units of {@code to}".
 * {@code rate(from, from)} is always {@link BigDecimal#ONE} (the same-currency fast path).
 *
 * @throws FxRateUnavailableException if no rate exists for the requested pair
 */
public interface ExchangeRateProvider {

    BigDecimal rate(Currency from, Currency to);
}
