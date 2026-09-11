-- Record WHAT a refund is for, not just that one is in flight.
--
-- V33 made REFUND_PENDING the durable claim written before the gateway is called. What it
-- did not record is what should happen once the refund is known to have gone through: the
-- cancellation reason the student should see, who asked for it, and whether the order is
-- to be cancelled at all (a support refund of a COMPLETED order leaves the order alone).
-- Without that, a refund settled later by the webhook or the reconciliation sweep could
-- move the money and then have no idea what to do with the order.
--
-- refund_reason      NULL means "refund the money, leave the order as it is".
-- refund_attempted_at is the LAST time the gateway was asked, not the first. The sweep
--                    only touches rows whose last attempt is old enough that an HTTP call
--                    cannot still be in flight, and a retry bumps it, which is what stops
--                    two sweeps (or two instances) sending the same refund together.
-- refund_attempts    caps automatic retries. After the cap a human takes over.
ALTER TABLE payments
    ADD COLUMN refund_attempted_at TIMESTAMP NULL AFTER reconciliation_reason,
    ADD COLUMN refund_requested_by BIGINT UNSIGNED NULL AFTER refund_attempted_at,
    ADD COLUMN refund_reason VARCHAR(200) NULL AFTER refund_requested_by,
    ADD COLUMN refund_attempts TINYINT UNSIGNED NOT NULL DEFAULT 0 AFTER refund_reason,
    ADD CONSTRAINT fk_payments_refund_requested_by
        FOREIGN KEY (refund_requested_by) REFERENCES users(id);

-- A row claimed under V33 code has no attempt time. It was asked once, and it was asked
-- no later than now, so the sweep may treat it as old.
UPDATE payments
SET refund_attempted_at = CURRENT_TIMESTAMP, refund_attempts = 1
WHERE status = 'REFUND_PENDING' AND refund_attempted_at IS NULL;
