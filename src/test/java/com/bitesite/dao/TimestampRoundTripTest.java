package com.bitesite.dao;

import com.bitesite.model.Order;
import com.bitesite.model.OtpChannel;
import com.bitesite.model.OtpCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the JDBC timezone boundary, which is invisible to every mock-based test in this
 * suite and was wrong in three separate places.
 *
 * <p>The connection URL declares {@code serverTimezone=UTC}. When the database server is
 * not actually on UTC, that makes the driver "correct" timestamps in opposite directions
 * on the way in and the way out: a {@code Timestamp} parameter is shifted one way, a
 * {@code Timestamp} read is shifted the other. Anything that mixes a server-side
 * {@code CURRENT_TIMESTAMP} write with a Java-side timestamp is then silently wrong by
 * the server's UTC offset.
 *
 * <p>These tests fail on a database running in any non-UTC zone if that mixing comes back,
 * and pass on a UTC one either way — so they are only as strong as the local server zone.
 * That is still worth having: it is exactly where these bugs were found, and CI on a UTC
 * box loses nothing by running them.
 */
@SpringBootTest
@ActiveProfiles("test")
class TimestampRoundTripTest {

    private static final String TOKEN_PREFIX = "TSRT-";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderDao orderDao;
    @Autowired private OtpCodeDao otpCodeDao;

    private Long tenantId;
    private Long outletId;
    private Long userId;

    @BeforeEach
    void seedIds() {
        outletId = jdbc.queryForObject("SELECT id FROM outlets ORDER BY id LIMIT 1", Long.class);
        tenantId = jdbc.queryForObject("SELECT tenant_id FROM outlets WHERE id = ?", Long.class, outletId);
        userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE tenant_id = ? ORDER BY id LIMIT 1", Long.class, tenantId);
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE token_no LIKE ?)",
                TOKEN_PREFIX + "%");
        jdbc.update("DELETE FROM orders WHERE token_no LIKE ?", TOKEN_PREFIX + "%");
        jdbc.update("DELETE FROM otp_codes WHERE user_id = ?", userId);
    }

    /** Inserts an order whose created_at is set by the database, minutes into the past. */
    private void insertOrderMinutesAgo(String token, int minutesAgo) {
        jdbc.update("INSERT INTO orders (tenant_id, outlet_id, user_id, token_no, total_amount, status, created_at) "
                        + "VALUES (?, ?, ?, ?, 10.00, 'AWAITING_PAYMENT', NOW() - INTERVAL ? MINUTE)",
                tenantId, outletId, userId, token, minutesAgo);
    }

    @Test
    void aServerWrittenTimestampReadsBackAsTheSameWallClockValue() {
        insertOrderMinutesAgo(TOKEN_PREFIX + "READ", 0);
        Long id = jdbc.queryForObject("SELECT id FROM orders WHERE token_no = ?", Long.class, TOKEN_PREFIX + "READ");
        String asStoredOnTheServer = jdbc.queryForObject(
                "SELECT CAST(created_at AS CHAR) FROM orders WHERE id = ?", String.class, id);

        Order order = orderDao.findByIdAndTenantId(id, tenantId).orElseThrow();

        // Reading through Timestamp instead of LocalDateTime shifted this by the server's
        // UTC offset, which is what put every order in the UI 5h30m into the future.
        assertThat(order.getCreatedAt()).isEqualTo(LocalDateTime.parse(asStoredOnTheServer.replace(' ', 'T')));
    }

    @Test
    void theExpirySweepUsesTheRealTimeoutNotTheTimeoutPlusTheServersUtcOffset() {
        insertOrderMinutesAgo(TOKEN_PREFIX + "FRESH", 5);
        insertOrderMinutesAgo(TOKEN_PREFIX + "STALE", 20);

        List<String> expired = orderDao.findExpiredAwaitingPayment(10).stream()
                .map(Order::getTokenNo)
                .filter(t -> t.startsWith(TOKEN_PREFIX))
                .toList();

        // With a Java-side cutoff this returned neither: the comparison value landed hours
        // in the past, so a 10-minute timeout only caught orders older than ~5h40m.
        assertThat(expired).containsExactly(TOKEN_PREFIX + "STALE");
    }

    @Test
    void anOtpExpiryRoundTripsUnchanged() {
        // Truncated to seconds: the column is TIMESTAMP, with no fractional part to keep.
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(10).withNano(0);

        otpCodeDao.save(OtpCode.builder()
                .userId(userId).channel(OtpChannel.EMAIL).codeHash("hash").expiresAt(expiresAt).build());
        OtpCode loaded = otpCodeDao.findLatest(userId, OtpChannel.EMAIL).orElseThrow();

        // This one used to survive by accident: the write shifted one way, the read shifted
        // back. Making reads zone-free without the write would have expired every OTP instantly.
        assertThat(loaded.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(loaded.getExpiresAt()).isAfter(LocalDateTime.now());
    }

    @Test
    void aTokenUsedOnAnEarlierDayCanBeIssuedAgainToday() {
        // The bug this guards: uniqueness used to span all time against a 9,000-value pool
        // with no purge, so a tenant's tokens ran out permanently after about a month.
        String token = TOKEN_PREFIX + "DUP";
        jdbc.update("INSERT INTO orders (tenant_id, outlet_id, user_id, token_no, total_amount, status, created_at) "
                        + "VALUES (?, ?, ?, ?, 10.00, 'COMPLETED', NOW() - INTERVAL 3 DAY)",
                tenantId, outletId, userId, token);

        assertThat(orderDao.existsTokenForTenantToday(tenantId, token)).isFalse();

        // And the constraint agrees: re-inserting it with today's date must be accepted.
        jdbc.update("INSERT INTO orders (tenant_id, outlet_id, user_id, token_no, total_amount, status) "
                        + "VALUES (?, ?, ?, ?, 10.00, 'AWAITING_PAYMENT')",
                tenantId, outletId, userId, token);
        assertThat(orderDao.existsTokenForTenantToday(tenantId, token)).isTrue();
    }

    @Test
    void thatSameTokenStillCannotBeIssuedTwiceOnOneDay() {
        String token = TOKEN_PREFIX + "SAME";
        jdbc.update("INSERT INTO orders (tenant_id, outlet_id, user_id, token_no, total_amount, status) "
                        + "VALUES (?, ?, ?, ?, 10.00, 'AWAITING_PAYMENT')",
                tenantId, outletId, userId, token);

        assertThat(orderDao.existsTokenForTenantToday(tenantId, token)).isTrue();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO orders (tenant_id, outlet_id, user_id, token_no, total_amount, status) "
                        + "VALUES (?, ?, ?, ?, 10.00, 'AWAITING_PAYMENT')",
                tenantId, outletId, userId, token))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
