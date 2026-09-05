-- FCM registration tokens for the native Android apps (order-ready / cancelled alerts).
--
-- Deliberately its own table rather than more columns on push_subscriptions. The two
-- channels share nothing but a user_id: Web Push identifies a device by an endpoint URL
-- plus a p256dh/auth key pair, FCM by a single opaque token, and the send paths, the
-- libraries and the "this device is gone" signals are different for each. Folding them
-- together would have meant three nullable columns and a discriminator on every read.
--
-- Both tables are consulted for the same user, which is correct rather than duplicative:
-- someone signed in on the phone app and on a laptop browser holds one row in each and
-- wants the alert in both places.
CREATE TABLE fcm_tokens (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    token VARCHAR(512) NOT NULL,
    -- Which app produced the token. Only 'android' today; recorded so a later iOS build
    -- does not need a migration to be told apart.
    platform VARCHAR(16) NOT NULL DEFAULT 'android',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- FCM reissues a token to the same install, and a shared phone can sign in as a
    -- second student. Unique on the token lets both cases re-point the existing row at
    -- whoever holds it now instead of accumulating rows that notify the wrong person.
    CONSTRAINT uq_fcm_token UNIQUE (token),
    CONSTRAINT fk_fcm_token_user FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_fcm_token_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
