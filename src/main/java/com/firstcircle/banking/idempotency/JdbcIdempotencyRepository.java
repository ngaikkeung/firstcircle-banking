package com.firstcircle.banking.idempotency;

import com.firstcircle.banking.db.DataAccessException;
import com.firstcircle.banking.db.UniqueViolationException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC-backed {@link IdempotencyRepository}. The {@code request_key} PRIMARY KEY is the at-most-once
 * gate; {@link #claim} translates a UNIQUE violation (SQLSTATE {@code 23505}) into a
 * {@link UniqueViolationException} so the caller can treat it as a lost race.
 */
public final class JdbcIdempotencyRepository implements IdempotencyRepository {

    private static final String SELECT_BY_KEY =
            "SELECT fingerprint, result_kind, result_ref FROM idempotency WHERE request_key = ?";
    private static final String INSERT =
            "INSERT INTO idempotency (request_key, fingerprint, result_kind, result_ref, created_at) "
            + "VALUES (?, ?, ?, ?, ?)";
    private static final String COUNT = "SELECT COUNT(*) FROM idempotency";

    @Override
    public Optional<IdempotencyRecord> findByKey(IdempotencyKey key, Connection c) {
        try (PreparedStatement ps = c.prepareStatement(SELECT_BY_KEY)) {
            ps.setString(1, key.value());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new IdempotencyRecord(
                        rs.getString("fingerprint"),
                        IdempotencyResultKind.valueOf(rs.getString("result_kind")),
                        rs.getObject("result_ref", UUID.class)));
            }
        } catch (SQLException e) {
            throw new DataAccessException(e);
        }
    }

    @Override
    public void claim(IdempotencyKey key, String fingerprint, IdempotencyResultKind resultKind,
                      UUID resultRef, Connection c) {
        try (PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setString(1, key.value());
            ps.setString(2, fingerprint);
            ps.setString(3, resultKind.name());
            ps.setObject(4, resultRef);
            ps.setObject(5, OffsetDateTime.now(ZoneOffset.UTC));
            ps.executeUpdate();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new UniqueViolationException(e);
            }
            throw new DataAccessException(e);
        }
    }

    @Override
    public long count(Connection c) {
        try (PreparedStatement ps = c.prepareStatement(COUNT);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new DataAccessException(e);
        }
    }
}
