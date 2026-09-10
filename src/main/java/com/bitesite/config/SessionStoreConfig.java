package com.bitesite.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.support.TransactionOperations;

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

    /**
     * Stops Spring Session opening a transaction around every single session statement.
     *
     * <p>This was measured as the largest remaining cost in the app: 9 of the 14.2 database
     * statements in an outlet queue poll, and 9 of the 18.3 in a page render, were
     * transaction bookkeeping rather than anything to do with answering the request. Roughly
     * 70% of the database work on a render and 80% on a poll. Against a database in a
     * different region from the app, each of those is a round trip measured at about 48ms.
     *
     * <p>The bean NAME is the entire mechanism and is not arbitrary: Spring Session looks for
     * exactly {@code springSessionTransactionOperations}. Rename it and this silently stops
     * applying, with no error and no clue beyond the statement count going back up.
     *
     * <p>THE TRADE, stated because it is real. Session saves lose atomicity: a save that
     * writes several attributes can now be interrupted part-written rather than rolling back
     * as one unit. That is defensible for this data and not for most data. A session holds
     * the security context and cart state, both of which are rebuilt from scratch on the next
     * request if they are torn — nothing here is a ledger, and nothing downstream reconciles
     * against it. An order or a payment would never be given this treatment.
     *
     * <p>Reverting is deleting this method.
     */
    @Bean
    public TransactionOperations springSessionTransactionOperations() {
        return TransactionOperations.withoutTransaction();
    }

    @Bean
    public SessionRepositoryCustomizer<JdbcIndexedSessionRepository> sessionAttributeUpsert() {
        return repository -> repository.setCreateSessionAttributeQuery(UPSERT_SESSION_ATTRIBUTE);
    }
}
