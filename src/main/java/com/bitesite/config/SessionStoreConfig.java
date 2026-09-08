package com.bitesite.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

/**
 * Makes writing a session attribute idempotent.
 *
 * <p>Spring Session's default insert is a plain {@code INSERT}, and two saves of the same
 * session attribute race: whichever loses gets {@code Duplicate entry '<id>-org.springframework.securit'
 * for key 'spring_session_attributes.PRIMARY'} and the request dies with a 500. Production hit
 * exactly that writing {@code SecurityContext}, and the page it killed was the access-denied
 * error page — so a student who tripped a permission check got a blank 500 instead of the
 * "you can't go there" screen that was the whole point of the redirect.
 *
 * <p>The 64-character key in that message is MySQL truncating its own error text, not a short
 * column: the primary key is {@code (SESSION_PRIMARY_ID, ATTRIBUTE_NAME)} over
 * {@code VARCHAR(200)} and holds full-length names fine. Worth stating because the truncation
 * points at "org.springframework.securit" and invites a migration to widen a column that is
 * already wide enough.
 *
 * <p>Last write wins on conflict, which is what the losing save was trying to do anyway —
 * both racers are storing the same session's current state, so neither ordering loses data
 * the other needed.
 *
 * <p>Uses the row-alias form rather than the older {@code VALUES(col)} function. Both work on
 * the production server (MySQL 8.0.21; the alias needs 8.0.19+), but {@code VALUES(col)} is
 * deprecated and warns on every insert against a newer server — including the 9.x most
 * developers have locally.
 */
@Configuration
public class SessionStoreConfig {

    private static final String UPSERT_SESSION_ATTRIBUTE = """
            INSERT INTO %TABLE_NAME%_ATTRIBUTES (SESSION_PRIMARY_ID, ATTRIBUTE_NAME, ATTRIBUTE_BYTES)
            VALUES (?, ?, ?) AS new
            ON DUPLICATE KEY UPDATE ATTRIBUTE_BYTES = new.ATTRIBUTE_BYTES
            """;

    @Bean
    public SessionRepositoryCustomizer<JdbcIndexedSessionRepository> sessionAttributeUpsert() {
        return repository -> repository.setCreateSessionAttributeQuery(UPSERT_SESSION_ATTRIBUTE);
    }
}
