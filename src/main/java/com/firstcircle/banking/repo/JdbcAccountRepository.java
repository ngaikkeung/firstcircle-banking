package com.firstcircle.banking.repo;

import com.firstcircle.banking.db.DataAccessException;
import com.firstcircle.banking.domain.Account;
import com.firstcircle.banking.domain.AccountId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC-backed {@link AccountRepository}. Maps the immutable {@link Account} value to/from the
 * {@code accounts} table. All methods run against the caller's {@link Connection}.
 */
public final class JdbcAccountRepository implements AccountRepository {

    private static final String SELECT_BY_ID =
            "SELECT id, owner_name, currency, balance_minor FROM accounts WHERE id = ?";
    private static final String SELECT_FOR_UPDATE = SELECT_BY_ID + " FOR UPDATE";
    private static final String INSERT =
            "INSERT INTO accounts (id, owner_name, currency, balance_minor) VALUES (?, ?, ?, ?)";
    private static final String UPDATE = "UPDATE accounts SET balance_minor = ? WHERE id = ?";

    @Override
    public Optional<Account> findForUpdate(AccountId id, Connection c) {
        return select(SELECT_FOR_UPDATE, id, c);
    }

    @Override
    public Optional<Account> findById(AccountId id, Connection c) {
        return select(SELECT_BY_ID, id, c);
    }

    @Override
    public void insert(Account account, Connection c) {
        try (PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setObject(1, account.id().value());
            ps.setString(2, account.ownerName());
            ps.setString(3, account.currency().getCurrencyCode());
            ps.setLong(4, account.balanceMinor());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException(e);
        }
    }

    @Override
    public void update(Account account, Connection c) {
        try (PreparedStatement ps = c.prepareStatement(UPDATE)) {
            ps.setLong(1, account.balanceMinor());
            ps.setObject(2, account.id().value());
            int rows = ps.executeUpdate();
            if (rows == 0) {
                throw new DataAccessException("account not found on update: " + account.id());
            }
        } catch (SQLException e) {
            throw new DataAccessException(e);
        }
    }

    private Optional<Account> select(String sql, AccountId id, Connection c) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id.value());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataAccessException(e);
        }
    }

    private static Account map(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        return new Account(
                AccountId.of(id),
                rs.getString("owner_name"),
                Currency.getInstance(rs.getString("currency")),
                rs.getLong("balance_minor"));
    }
}
