package com.bitesite.dao;

import com.bitesite.model.PushSubscription;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class PushSubscriptionDaoImpl implements PushSubscriptionDao {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<PushSubscription> ROW_MAPPER = (rs, rowNum) -> PushSubscription.builder()
            .id(rs.getLong("id"))
            .userId(rs.getLong("user_id"))
            .endpoint(rs.getString("endpoint"))
            .p256dhKey(rs.getString("p256dh_key"))
            .authKey(rs.getString("auth_key"))
            .createdAt(rs.getObject("created_at", LocalDateTime.class))
            .build();

    @Override
    public void save(PushSubscription subscription) {
        jdbcTemplate.update(
                "INSERT INTO push_subscriptions (user_id, endpoint, p256dh_key, auth_key) VALUES (?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE user_id = VALUES(user_id), p256dh_key = VALUES(p256dh_key), "
                        + "auth_key = VALUES(auth_key)",
                subscription.getUserId(), subscription.getEndpoint(),
                subscription.getP256dhKey(), subscription.getAuthKey());
    }

    @Override
    public List<PushSubscription> findByUserId(Long userId) {
        return jdbcTemplate.query(
                "SELECT * FROM push_subscriptions WHERE user_id = ?", ROW_MAPPER, userId);
    }

    @Override
    public void deleteByEndpoint(String endpoint) {
        jdbcTemplate.update("DELETE FROM push_subscriptions WHERE endpoint = ?", endpoint);
    }
}
