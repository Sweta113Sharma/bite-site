package com.bitesite.dao;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards V15, which is the one migration in this project that can fail halfway and leave
 * the database working but wrong.
 *
 * <p>It drops three CHECK constraints, converts data, then re-adds them. MySQL auto-commits
 * DDL, so Flyway cannot roll a partial run back — a failure after the drops leaves a
 * database that accepts any role string at all, and nothing in the application would
 * notice until something wrote a value the Java enum cannot parse. That failure surfaces
 * inside the row mapper during {@code findByEmail}, so it presents as a 500 on login
 * rather than anything that names the real cause.
 *
 * <p>The test profile runs the seed files too, so this also proves the conversion actually
 * caught the seeded CANTEEN_STAFF accounts rather than merely being written down.
 */
@SpringBootTest
@ActiveProfiles("test")
class RoleVocabularyMigrationTest {

    @Autowired private JdbcTemplate jdbc;

    @Test
    void noRowAnywhereStillHoldsTheRetiredRole() {
        // Any survivor is a live login failure: Role.valueOf has no CANTEEN_STAFF to return.
        assertThat(count("SELECT COUNT(*) FROM users WHERE role = 'CANTEEN_STAFF'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM users WHERE active_role = 'CANTEEN_STAFF'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM user_roles WHERE role = 'CANTEEN_STAFF'")).isZero();
    }

    @Test
    void theSeededCanteenAccountsBecameManagers() {
        // Proves the UPDATEs ran against real rows. If the seed ordering ever changed so
        // that V15 ran before the accounts existed, this would be zero and the assertion
        // above would pass vacuously.
        assertThat(count("SELECT COUNT(*) FROM users WHERE role = 'CANTEEN_MANAGER'"))
                .isGreaterThan(0);
    }

    @Test
    void theCheckConstraintsWerePutBack() {
        // The half-migrated state this exists to catch: constraints dropped, never re-added.
        //
        // Asserted on the message, not the exception type. MySQL reports a CHECK violation
        // as error 3819, which Spring does not map to DataIntegrityViolationException — it
        // arrives as an UncategorizedSQLException. Pinning the type here would assert
        // Spring's error-code table rather than the thing that matters, which is that the
        // database refused the write and named the constraint that stopped it.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO user_roles (user_id, role) "
                        + "SELECT id, 'CANTEEN_STAFF' FROM users ORDER BY id LIMIT 1"))
                .hasMessageContaining("chk_user_roles_role");
    }

    @Test
    void bothNewRoleNamesAreAccepted() {
        // The mirror of the above — a constraint re-added with the wrong vocabulary would
        // reject the values the application now writes.
        // Scoped to DATABASE(). information_schema is server-wide, so without this the
        // assertion counts every schema on the machine that happens to hold a constraint
        // by that name — it passed only while this was the sole migrated database, and
        // started failing the moment the dev database was migrated too. The count was
        // never the point; the vocabulary is.
        assertThat(count("SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS "
                + "WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_users_role' "
                + "AND CHECK_CLAUSE LIKE '%CANTEEN_MANAGER%' AND CHECK_CLAUSE LIKE '%CANTEEN_OPERATOR%'"))
                .isEqualTo(1);
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }
}
