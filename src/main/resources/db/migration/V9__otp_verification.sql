-- Replaces link-based email verification with a 6-digit OTP, sent for both email and
-- phone, verified through the same flow. The old token/link table is no longer used.
DROP TABLE email_verification_tokens;

-- Same reasoning as email_verified (V5): defaults TRUE so every existing row is
-- unaffected — only newly self-registered students who supplied a phone number get
-- gated, and only once SMS is actually configured (see UserService.registerStudent /
-- SmsService.isConfigured).
ALTER TABLE users ADD COLUMN phone_verified BOOLEAN NOT NULL DEFAULT TRUE AFTER email_verified;

CREATE TABLE otp_codes (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    channel VARCHAR(10) NOT NULL,
    code_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_otp_codes_user FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_otp_codes_user_channel (user_id, channel)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
