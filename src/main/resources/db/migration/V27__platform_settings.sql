-- Platform-wide key/value settings, owned by SUPER_ADMIN.
--
-- Deliberately NOT tech_config: that table is tenant-scoped (tenant_id NOT NULL with an
-- FK to tenants, unique on (tenant_id, config_key)), which is right for per-college
-- settings and wrong for anything describing BiteSite itself. The grievance officer is
-- one named person for the whole platform, and the people allowed to set them hold
-- tenant_id = NULL, so there is no tenant to hang the row off without inventing a fake
-- one.
--
-- config_key is the primary key: one row per setting, platform-wide, no duplicates.
CREATE TABLE platform_settings (
    config_key VARCHAR(100) NOT NULL,
    config_value VARCHAR(500) NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
