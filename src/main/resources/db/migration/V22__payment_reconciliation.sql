-- Money captured against an order that could not accept it.
--
-- Unpaid orders expire on a timer, and EXPIRED was terminal in every direction. So a
-- capture arriving after the sweep — a slow bank OTP, a reconnecting phone, a webhook
-- retried after a restart — marked the payment CAPTURED, silently failed the transition
-- to PAID, and returned success. The student was charged, had no order, and nothing in
-- the system said so. The only trace was a CAPTURED payment whose order was EXPIRED, and
-- nothing joined those two, so nobody would find it without going to the database.
--
-- Most of those are now revived rather than flagged (see OrderService.confirmPayment).
-- This is for the remainder: money taken against an order that genuinely cannot be
-- honoured, which needs a human and a refund rather than a retry.
ALTER TABLE payments
    ADD COLUMN needs_reconciliation BOOLEAN NOT NULL DEFAULT FALSE AFTER status,
    ADD COLUMN reconciliation_reason VARCHAR(200) NULL AFTER needs_reconciliation;

-- The admin view filters on the flag alone, and the count on the overview runs on every
-- admin page load.
CREATE INDEX idx_payments_reconciliation ON payments (needs_reconciliation, created_at);
