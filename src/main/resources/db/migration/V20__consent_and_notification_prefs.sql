-- Consent records and notification preferences.
--
-- The published privacy policy tells students they can access and delete their data, and
-- registration says "by creating an account you agree to our terms". Both were claims with
-- nothing behind them: no consent was recorded, no version was pinned, and there was no
-- access path at all — only deletion.
--
-- Consent is stored per purpose rather than as one flag, because withdrawing agreement to
-- optional notifications is not the same act as withdrawing the terms you need to hold an
-- account at all. And the policy version is recorded with it: consent to a document is
-- meaningless without knowing which text was agreed to.

CREATE TABLE consents (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    purpose VARCHAR(40) NOT NULL,
    policy_version VARCHAR(20) NOT NULL,
    granted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Withdrawal is recorded, not deleted. "They withdrew on the 3rd" is the fact that
    -- matters; removing the row would leave no evidence consent was ever given or taken back.
    withdrawn_at TIMESTAMP NULL,
    CONSTRAINT fk_consents_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT chk_consents_purpose CHECK (purpose IN ('TERMS','ORDER_NOTIFICATIONS','MARKETING')),
    INDEX idx_consents_user (user_id, purpose)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Per-category notification preferences.
--
-- Push was all-or-nothing per device, and the toggle read its state from the browser
-- rather than the server, so it desynced whenever a subscription was removed server-side.
-- These columns are the server's answer, independent of any one device.
--
-- Order updates default on: a student who has paid for food needs to know it is ready, and
-- that is the notification the product exists to send. Marketing defaults off — silence is
-- not consent.
ALTER TABLE users
    ADD COLUMN notify_order_updates BOOLEAN NOT NULL DEFAULT TRUE AFTER is_active,
    ADD COLUMN notify_marketing BOOLEAN NOT NULL DEFAULT FALSE AFTER notify_order_updates;

-- Data-principal requests: the queue behind the "your rights" section of the policy.
CREATE TABLE data_requests (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    tenant_id BIGINT UNSIGNED NULL,
    kind VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    note VARCHAR(500) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP NULL,
    CONSTRAINT fk_data_requests_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_data_requests_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT chk_data_requests_kind CHECK (kind IN ('ACCESS','CORRECTION','ERASURE')),
    CONSTRAINT chk_data_requests_status CHECK (status IN ('OPEN','IN_PROGRESS','RESOLVED','REJECTED')),
    INDEX idx_data_requests_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
