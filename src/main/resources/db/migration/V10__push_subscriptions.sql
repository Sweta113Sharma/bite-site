-- Web Push subscriptions (order-ready / cancelled alerts). A user can hold more than one
-- row here — one per browser/device they've enabled notifications on.
CREATE TABLE push_subscriptions (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    endpoint VARCHAR(512) NOT NULL,
    p256dh_key VARCHAR(255) NOT NULL,
    auth_key VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_push_sub_endpoint UNIQUE (endpoint),
    CONSTRAINT fk_push_sub_user FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_push_sub_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
