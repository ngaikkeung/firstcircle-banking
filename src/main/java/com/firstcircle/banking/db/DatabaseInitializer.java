package com.firstcircle.banking.db;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.h2.tools.RunScript;

/**
 * Applies {@code src/main/resources/schema.sql} to a {@link DataSource}. Called once at startup
 * (and once per fresh test database) before any banking operation.
 *
 * <p>Uses H2's {@link RunScript}, which parses the script properly — handling {@code --} comments
 * and statement terminators — rather than a naive split that would break on a {@code ;} inside a
 * comment.
 */
public final class DatabaseInitializer {

    private DatabaseInitializer() {
    }

    public static void init(DataSource dataSource) {
        try (InputStream in = DatabaseInitializer.class.getResourceAsStream("/schema.sql")) {
            if (in == null) {
                throw new IllegalStateException("schema.sql not found on classpath");
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
                 Connection c = dataSource.getConnection()) {
                RunScript.execute(c, reader);
            }
        } catch (SQLException e) {
            throw new DataAccessException("schema initialisation failed", e);
        } catch (IOException e) {
            throw new DataAccessException("failed to read schema.sql", e);
        }
    }
}
