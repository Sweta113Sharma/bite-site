package com.bitesite.dao;

import com.bitesite.model.OtpChannel;
import com.bitesite.model.OtpCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class OtpCodeDaoImpl implements OtpCodeDao {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<OtpCode> ROW_MAPPER = (rs, rowNum) -> OtpCode.builder()
            .id(rs.getLong("id"))
            .userId(rs.getLong("user_id"))
            .channel(OtpChannel.valueOf(rs.getString("channel")))
            .codeHash(rs.getString("code_hash"))
            .expiresAt(rs.getTimestamp("expires_at").toLocalDateTime())
            .attempts(rs.getInt("attempts"))
            .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
            .build();

    @Override
    public OtpCode save(OtpCode code) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO otp_codes (user_id, channel, code_hash, expires_at) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, code.getUserId());
            ps.setString(2, code.getChannel().name());
            ps.setString(3, code.getCodeHash());
            ps.setTimestamp(4, Timestamp.valueOf(code.getExpiresAt()));
            return ps;
        }, keyHolder);
        code.setId(keyHolder.getKey().longValue());
        return code;
    }

    @Override
    public Optional<OtpCode> findLatest(Long userId, OtpChannel channel) {
        return jdbcTemplate.query(
                        "SELECT * FROM otp_codes WHERE user_id = ? AND channel = ? ORDER BY created_at DESC LIMIT 1",
                        ROW_MAPPER, userId, channel.name())
                .stream().findFirst();
    }

    @Override
    public void incrementAttempts(Long id) {
        jdbcTemplate.update("UPDATE otp_codes SET attempts = attempts + 1 WHERE id = ?", id);
    }

    @Override
    public void deleteByUserIdAndChannel(Long userId, OtpChannel channel) {
        jdbcTemplate.update("DELETE FROM otp_codes WHERE user_id = ? AND channel = ?", userId, channel.name());
    }
}
