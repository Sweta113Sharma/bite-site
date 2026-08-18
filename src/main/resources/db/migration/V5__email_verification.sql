-- email_verified defaults to TRUE so every existing row (and every admin-provisioned
-- canteen staff / tech manager / super admin account going forward) is unaffected —
-- only self-registered students get gated, and only once SMTP is actually configured
-- (see UserService.registerStudent / EmailService.isConfigured).
ALTER TABLE users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT TRUE AFTER is_active;

CREATE TABLE email_verification_tokens (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    token VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_evt_token UNIQUE (token),
    CONSTRAINT fk_evt_user FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_evt_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
