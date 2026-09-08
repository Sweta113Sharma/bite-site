package com.bitesite.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Runs the session-attribute insert this app is actually configured with, twice, against a
 * real MySQL — the collision that returned a 500 in production.
 *
 * <p>Mocks cannot catch this. The bug was that Spring Session's stock {@code INSERT} is not
 * idempotent, so it only shows up when a real server enforces a real primary key, and only
 * on the second write. See {@link SessionStoreConfig} for how it was reaching users.
 *
 * <p>Reads the SQL off the repository bean rather than restating it, so the test fails if
 * the customizer stops being applied — the realistic regression, since nothing else in the
 * app would notice the stock query coming back until it 500ed again.
 */
@SpringBootTest
@ActiveProfiles("test")
class SessionAttributeUpsertTest {

    private static final String ATTRIBUTE_NAME = "org.springframework.security.core.context.SecurityContext";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private JdbcIndexedSessionRepository sessionRepository;

    private final String primaryId = UUID.randomUUID().toString();

    @AfterEach
    void removeSession() {
        jdbc.update("DELETE FROM SPRING_SESSION WHERE PRIMARY_ID = ?", primaryId);
    }

    private String configuredQuery() {
        return (String) ReflectionTestUtils.getField(sessionRepository, "createSessionAttributeQuery");
    }

    @Test
    void tableNamePlaceholderIsSubstituted() {
        assertThat(configuredQuery())
                .contains("SPRING_SESSION_ATTRIBUTES")
                .doesNotContain("%TABLE_NAME%");
    }

    @Test
    void writingTheSameAttributeTwiceDoesNotFail() {
        String sql = configuredQuery();
        jdbc.update("INSERT INTO SPRING_SESSION "
                + "(PRIMARY_ID, SESSION_ID, CREATION_TIME, LAST_ACCESS_TIME, MAX_INACTIVE_INTERVAL, EXPIRY_TIME) "
                + "VALUES (?, ?, 0, 0, 1800, 0)", primaryId, UUID.randomUUID().toString());

        jdbc.update(sql, primaryId, ATTRIBUTE_NAME, "first".getBytes(StandardCharsets.UTF_8));

        // The losing half of the race. On the stock query this is the DuplicateKeyException
        // that killed the request.
        assertThatCode(() -> jdbc.update(sql, primaryId, ATTRIBUTE_NAME,
                "second".getBytes(StandardCharsets.UTF_8))).doesNotThrowAnyException();

        byte[] stored = jdbc.queryForObject(
                "SELECT ATTRIBUTE_BYTES FROM SPRING_SESSION_ATTRIBUTES "
                        + "WHERE SESSION_PRIMARY_ID = ? AND ATTRIBUTE_NAME = ?",
                byte[].class, primaryId, ATTRIBUTE_NAME);

        assertThat(new String(stored, StandardCharsets.UTF_8))
                .as("the later save should win, not be dropped")
                .isEqualTo("second");
    }
}
