-- Backs RateLimiter with the database instead of process memory, so login/checkout
-- throttling is consistent across app restarts and across more than one app instance.
CREATE TABLE rate_limit_window (
    rate_key VARCHAR(191) NOT NULL PRIMARY KEY,
    window_start TIMESTAMP(3) NOT NULL,
    attempt_count INT UNSIGNED NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
