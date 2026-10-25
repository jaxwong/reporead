// Owns real PostgreSQL runtime cancellation/rollback checks; connection-login timeout is tested separately.

package com.reporead;

import static org.junit.jupiter.api.Assertions.*;

import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owns configured query/lock/socket failure checks against the real test database, including rollback and the next run.
 */
class DatabaseRuntimeTest extends SharedSpringTest {
    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate configured;

    @Autowired
    TransactionTemplate transaction;

    /**
     * Query cancellation must also bound lock waits and must not publish an earlier write in the failed transaction.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void timedOutQueriesAndLockWaitsRollBackAndTheNextTransactionWorks(boolean lockWait) throws Exception {
        TestEnvironment.reset(db);
        long id = db.sql("INSERT INTO users (github_user_id, login) VALUES (42, 'test-only-before') RETURNING id")
                .query(Long.class)
                .single();
        long partialId = db.sql(
                        "INSERT INTO users (github_user_id, login) VALUES (43, 'test-only-before') RETURNING id")
                .query(Long.class)
                .single();
        assertEquals(15, configured.getQueryTimeout());
        try (var holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                if (lockWait) {
                    try (var statement =
                            holder.prepareStatement("UPDATE users SET login = 'test-only-holder' WHERE id = ?")) {
                        statement.setLong(1, id);
                        assertEquals(1, statement.executeUpdate());
                    }
                }
                long start = System.nanoTime();
                var failure = assertTimeoutPreemptively(
                        Duration.ofSeconds(20),
                        () -> assertThrows(
                                QueryTimeoutException.class,
                                () -> transaction.execute(status -> {
                                    db.sql("UPDATE users SET login = 'test-only-partial' WHERE id = :id")
                                            .param("id", partialId)
                                            .update();
                                    return lockWait
                                            ? db.sql(
                                                            "UPDATE users SET login = 'test-only-blocked' WHERE id = :id RETURNING login")
                                                    .param("id", id)
                                                    .query(String.class)
                                                    .single()
                                            : db.sql("SELECT 'test-only-finished' FROM pg_sleep(30)")
                                                    .query(String.class)
                                                    .single();
                                })));
                assertEquals(
                        "57014",
                        assertInstanceOf(SQLException.class, failure.getMostSpecificCause())
                                .getSQLState());
                assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(20)) < 0);
            } finally {
                holder.rollback();
            }
        }
        assertEquals(
                "test-only-before",
                db.sql("SELECT login FROM users WHERE id = :id")
                        .param("id", partialId)
                        .query(String.class)
                        .single());
        assertEquals(
                "test-only-before",
                db.sql("SELECT login FROM users WHERE id = :id")
                        .param("id", id)
                        .query(String.class)
                        .single());
        transaction.executeWithoutResult(status -> db.sql("UPDATE users SET login = 'test-only-after' WHERE id = :id")
                .param("id", id)
                .update());
        assertEquals(
                "test-only-after",
                db.sql("SELECT login FROM users WHERE id = :id")
                        .param("id", id)
                        .query(String.class)
                        .single());
    }

    /** Isolates the socket ceiling from JdbcTemplate's shorter statement deadline, using the real pooled driver. */
    @Test
    void aSilentRuntimeReadClosesItsConnectionAndTheNextConnectionWorks() throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            assertEquals(20_000, connection.getNetworkTimeout());
            // Raw JDBC deliberately bypasses the template's 15 s query cap to exercise only the socket cap.
            assertEquals(0, statement.getQueryTimeout());
            var failure = assertTimeoutPreemptively(
                    Duration.ofSeconds(25),
                    () -> assertThrows(SQLException.class, () -> statement.execute("SELECT pg_sleep(30)")));
            assertEquals("08006", failure.getSQLState());
            assertInstanceOf(SocketTimeoutException.class, failure.getCause());
            assertFalse(connection.isValid(1));
        }
        assertEquals(1, db.sql("SELECT 1").query(Integer.class).single());
    }
}
