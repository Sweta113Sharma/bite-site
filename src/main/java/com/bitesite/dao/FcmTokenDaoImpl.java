package com.bitesite.dao;

import com.bitesite.model.FcmToken;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class FcmTokenDaoImpl implements FcmTokenDao {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<FcmToken> ROW_MAPPER = (rs, rowNum) -> FcmToken.builder()
            .id(rs.getLong("id"))
            .userId(rs.getLong("user_id"))
            .token(rs.getString("token"))
            .platform(rs.getString("platform"))
            .createdAt(rs.getObject("created_at", LocalDateTime.class))
            .build();

    @Override
    public void save(FcmToken token) {
        jdbcTemplate.update(
                "INSERT INTO fcm_tokens (user_id, token, platform) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE user_id = VALUES(user_id), platform = VALUES(platform)",
                token.getUserId(), token.getToken(), token.getPlatform());
    }

    @Override
    public List<FcmToken> findByUserId(Long userId) {
        return jdbcTemplate.query("SELECT * FROM fcm_tokens WHERE user_id = ?", ROW_MAPPER, userId);
    }

    /** Unscoped. Kept for the send path, which deletes a token FCM has just reported as
     * unregistered — there is no user in hand there, and FCM saying a token is dead is
     * authority enough. Never call it from a request handler; use
     * {@link #deleteByTokenForUser} instead. */
    @Override
    public void deleteByToken(String token) {
        jdbcTemplate.update("DELETE FROM fcm_tokens WHERE token = ?", token);
    }

    @Override
    public int deleteByTokenForUser(String token, Long userId) {
        return jdbcTemplate.update(
                "DELETE FROM fcm_tokens WHERE token = ? AND user_id = ?", token, userId);
    }

    @Override
    public void deleteByUserId(Long userId) {
        jdbcTemplate.update("DELETE FROM fcm_tokens WHERE user_id = ?", userId);
    }
}
