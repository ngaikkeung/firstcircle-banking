package com.firstcircle.banking.exceptions;

import java.util.Currency;

/**
 * Raised when no exchange rate is available for a currency pair during a
 * cross-currency transfer. The transfer never mutates any balance in this case.
 */
public class FxRateUnavailableException extends BankingException {

    public FxRateUnavailableException(Currency from, Currency to) {
        super("No exchange rate available from " + from.getCurrencyCode()
                + " to " + to.getCurrencyCode());
    }
}
