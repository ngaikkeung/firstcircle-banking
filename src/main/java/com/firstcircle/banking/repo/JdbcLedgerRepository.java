package com.firstcircle.banking.repo;

import com.firstcircle.banking.db.DataAccessException;
import com.firstcircle.banking.db.SqlExceptions;
import com.firstcircle.banking.domain.AccountId;
import com.firstcircle.banking.domain.EntryType;
import com.firstcircle.banking.domain.LedgerEntry;
import com.firstcircle.banking.domain.Transaction;
import com.firstcircle.banking.domain.TransactionId;
import com.firstcircle.banking.domain.TransactionType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC-backed {@link LedgerRepository}. Stores each {@link Transaction} as one {@code transactions}
 * row plus its {@link LedgerEntry}s in {@code ledger_entries}, and reconstructs them (re-using the
 * validated domain factories) on read.
 */
public final class JdbcLedgerRepository implements LedgerRepository {

    private static final String INSERT_TX =
            "INSERT INTO transactions (id, seq, created_at, type, request_key) VALUES (?, ?, ?, ?, ?)";
    private static final String INSERT_ENTRY = "INSERT INTO ledger_entries "
            + "(transaction_id, account_id, currency, entry_type, signed_amount, ordinal) "
            + "VALUES (?, ?, ?, ?, ?, ?)";

    private static final String JOIN_SELECT =
            "SELECT t.id AS tx_id, t.seq, t.created_at, t.type, "
            + "e.account_id, e.currency, e.entry_type, e.signed_amount, e.ordinal "
            + "FROM transactions t LEFT JOIN ledger_entries e ON e.transaction_id = t.id ";

    @Override
    public void append(Transaction transaction, String requestKey, Connection c) {
        try (PreparedStatement txPs = c.prepareStatement(INSERT_TX)) {
            txPs.setObject(1, transaction.getId().getValue());
            txPs.setLong(2, transaction.getSequence());
            txPs.setObject(3, OffsetDateTime.ofInstant(transaction.getTimestamp(), ZoneOffset.UTC));
            txPs.setString(4, transaction.getType().name());
            txPs.setString(5, requestKey);
            txPs.executeUpdate();
        } catch (SQLException e) {
            throw SqlExceptions.wrap(e); // 23505 (duplicate request_key) -> UniqueViolationException
        }
        try (PreparedStatement ePs = c.prepareStatement(INSERT_ENTRY)) {
            List<LedgerEntry> entries = transaction.getEntries();
            for (int i = 0; i < entries.size(); i++) {
                LedgerEntry entry = entries.get(i);
                ePs.setObject(1, transaction.getId().getValue());
                ePs.setObject(2, entry.getAccount().getValue());
                ePs.setString(3, entry.getCurrency().getCurrencyCode());
                ePs.setString(4, entry.getType().name());
                ePs.setLong(5, entry.getSignedAmount());
                ePs.setInt(6, i);
                ePs.addBatch();
            }
            ePs.executeBatch();
        } catch (SQLException e) {
            throw SqlExceptions.wrap(e);
        }
    }

    @Override
    public Optional<Transaction> findById(TransactionId id, Connection c) {
        try (PreparedStatement ps = c.prepareStatement(JOIN_SELECT + " WHERE t.id = ? ORDER BY e.ordinal")) {
            ps.setObject(1, id.getValue());
            List<Transaction> rows = readGrouped(ps.executeQuery());
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        } catch (SQLException e) {
            throw new DataAccessException(e);
        }
    }

    @Override
    public Optional<Transaction> findByRequestKey(String requestKey, Connection c) {
        try (PreparedStatement ps = c.prepareStatement(JOIN_SELECT + " WHERE t.request_key = ? ORDER BY e.ordinal")) {
            ps.setString(1, requestKey);
            List<Transaction> rows = readGrouped(ps.executeQuery());
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        } catch (SQLException e) {
            throw new DataAccessException(e);
        }
    }

    @Override
    public List<Transaction> findAll(Connection c) {
        try (PreparedStatement ps = c.prepareStatement(JOIN_SELECT + " ORDER BY t.seq, e.ordinal")) {
            return readGrouped(ps.executeQuery());
        } catch (SQLException e) {
            throw new DataAccessException(e);
        }
    }

    @Override
    public List<LedgerEntry> entriesFor(AccountId id, Connection c) {
        String sql = "SELECT account_id, currency, entry_type, signed_amount FROM ledger_entries "
                + "WHERE account_id = ? ORDER BY id";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id.getValue());
            List<LedgerEntry> entries = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(mapEntry(rs));
                }
            }
            return entries;
        } catch (SQLException e) {
            throw new DataAccessException(e);
        }
    }

    /** Reads the join {@link ResultSet}, grouping rows back into balanced {@link Transaction}s. */
    private static List<Transaction> readGrouped(ResultSet rs) throws SQLException {
        List<Transaction> out = new ArrayList<>();
        UUID currentId = null;
        TransactionId id = null;
        long seq = 0L;
        Instant ts = null;
        TransactionType type = null;
        List<LedgerEntry> entries = new ArrayList<>();
        while (rs.next()) {
            UUID txId = rs.getObject("tx_id", UUID.class);
            if (currentId == null || !currentId.equals(txId)) {
                if (currentId != null) {
                    out.add(Transaction.create(id, seq, ts, type, entries));
                }
                currentId = txId;
                id = TransactionId.of(txId);
                seq = rs.getLong("seq");
                ts = toInstant(rs);
                type = TransactionType.valueOf(rs.getString("type"));
                entries = new ArrayList<>();
            }
            UUID accountId = rs.getObject("account_id", UUID.class);
            if (accountId != null) {
                entries.add(mapEntry(rs));
            }
        }
        if (currentId != null) {
            out.add(Transaction.create(id, seq, ts, type, entries));
        }
        return out;
    }

    private static Instant toInstant(ResultSet rs) throws SQLException {
        Object value = rs.getObject("created_at");
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        // Fallback: H2 may return a Timestamp depending on version/config.
        Timestamp ts = rs.getTimestamp("created_at");
        return ts.toInstant();
    }

    /** Reconstructs an entry via the validated factories (sign derived from the stored type). */
    private static LedgerEntry mapEntry(ResultSet rs) throws SQLException {
        AccountId account = AccountId.of(rs.getObject("account_id", UUID.class));
        Currency currency = Currency.getInstance(rs.getString("currency"));
        long signedAmount = rs.getLong("signed_amount");
        long magnitude = Math.abs(signedAmount);
        return rs.getString("entry_type").equals(EntryType.CREDIT.name())
                ? LedgerEntry.credit(account, currency, magnitude)
                : LedgerEntry.debit(account, currency, magnitude);
    }
}
