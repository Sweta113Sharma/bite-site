-- Let a payment say "we asked for a refund and do not know the outcome yet".
--
-- WHY THE STATE EXISTS
-- A refund is a network call that can succeed while telling you it failed. Razorpay
-- processes it, our HTTP call times out, we throw. If the only states are CAPTURED and
-- REFUNDED, that timeout has to be recorded as one or the other, and both are wrong:
--   CAPTURED says the money is still ours, so the next cancel refunds it a second time
--   REFUNDED says it definitely went back, which we do not know
--
-- REFUND_PENDING is written and COMMITTED BEFORE the gateway is called. It is the claim
-- that makes refunding exclusive, and it is also the honest answer after a timeout: we
-- asked, we do not know. The payment is flagged for reconciliation from there and a human
-- settles it against Razorpay rather than the system guessing.
--
-- A payment is never refunded again out of this state, which is the entire point.
--
-- The CHECK constraint is why this migration is needed at all: V1 pinned the five original
-- statuses, so writing REFUND_PENDING failed with "Check constraint chk_payments_status is
-- violated" rather than doing anything useful. Found by the concurrency test, which is the
-- only reason it was not discovered in production during a refund.
ALTER TABLE payments
    DROP CONSTRAINT chk_payments_status;

ALTER TABLE payments
    ADD CONSTRAINT chk_payments_status
        CHECK (status IN ('CREATED','AUTHORIZED','CAPTURED','REFUND_PENDING','FAILED','REFUNDED'));
