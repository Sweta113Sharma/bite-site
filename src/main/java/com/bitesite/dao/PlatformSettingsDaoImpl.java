package com.bitesite.dao;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class PlatformSettingsDaoImpl implements PlatformSettingsDao {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Map<String, String> findAll() {
        Map<String, String> out = new HashMap<>();
        jdbcTemplate.query("SELECT config_key, config_value FROM platform_settings",
                rs -> { out.put(rs.getString("config_key"), rs.getString("config_value")); });
        return out;
    }

    @Override
    public void upsert(String key, String value) {
        // Row-alias form rather than the older VALUES(col) used in TechConfigDaoImpl:
        // VALUES(col) is deprecated from MySQL 8.0.20 and warns on every write against a
        // newer server. The alias needs 8.0.19+, which production (8.0.21) has.
        jdbcTemplate.update(
                "INSERT INTO platform_settings (config_key, config_value) VALUES (?, ?) AS new "
                        + "ON DUPLICATE KEY UPDATE config_value = new.config_value",
                key, value);
    }
}
