package com.firstcircle.banking.ledger;

import com.firstcircle.banking.domain.AccountId;
import java.util.UUID;

/**
 * Well-known system ("contra") accounts used as the counterparty in double-entry postings so
 * that every transaction nets to zero per currency. These are internal bookkeeping identities;
 * they are referenced from ledger entries but are not customer-facing accounts and have no
 * balance tracked in the account repository.
 */
public final class ContraAccountIds {

    /** The vault/cash counterparty for deposits and withdrawals. */
    public static final AccountId CASH_CONTRA =
            AccountId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));

    /** The counterparty that absorbs FX positions and rounding residue in cross-currency transfers. */
    public static final AccountId FX_CONTRA =
            AccountId.of(UUID.fromString("00000000-0000-0000-0000-000000000002"));

    private ContraAccountIds() {
    }
}
