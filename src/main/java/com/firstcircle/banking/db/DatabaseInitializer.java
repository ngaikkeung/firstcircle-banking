package com.firstcircle.banking.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * Applies {@code src/main/resources/schema.sql} to a {@link DataSource}. Called once at startup
 * (and once per fresh test database) before any banking operation.
 *
 * <p>The script is split on {@code ;} and executed statement-by-statement; the schema contains no
 * string literals with semicolons, so this split is safe.
 */
public final class DatabaseInitializer {

    private DatabaseInitializer() {
    }

    public static void init(DataSource dataSource) {
        String sql = loadSchema();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String stmt : sql.split(";")) {
                String trimmed = stmt.trim();
                if (!trimmed.isEmpty()) {
                    s.execute(trimmed);
                }
            }
        } catch (SQLException e) {
            throw new DataAccessException("schema initialisation failed", e);
        }
    }

    private static String loadSchema() {
        try (InputStream in = DatabaseInitializer.class.getResourceAsStream("/schema.sql")) {
            if (in == null) {
                throw new IllegalStateException("schema.sql not found on classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read schema.sql", e);
        }
    }
}
