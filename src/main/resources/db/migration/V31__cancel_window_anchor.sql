-- Start the student's cancellation window when they are TOLD they paid, not when the
-- money landed.
--
-- THE BUG THIS FIXES
-- Razorpay confirms a payment twice: a server-to-server `payment.captured` webhook, and
-- the browser callback the student's own device sends on its way back from the payment
-- sheet. Whichever arrives first stamps paid_at, and the webhook usually wins — it is a
-- direct call between two servers, while the student is still watching Razorpay's success
-- animation and waiting on a redirect. The window was measured from paid_at, so by the
-- time the order screen actually rendered, several of the student's seconds were already
-- spent. On a slow return the whole window could be gone before the button was ever seen.
--
-- WHY A SEPARATE COLUMN RATHER THAN MOVING paid_at
-- paid_at is a money fact: the moment the gateway captured the payment. It belongs in
-- reconciliation and settlement, and rewriting it to a later time to fix a countdown
-- would corrupt the answer to "when were we actually paid". These are two different
-- facts, so they get two different columns.
--
-- NULL is the normal state for an order whose student never came back — a closed browser,
-- a dead battery. Everything then falls back to paid_at, which is exactly the old
-- behaviour, so the kitchen is still guaranteed to be shown the order within one window
-- of capture no matter what the student's device does. Nothing can be hidden forever.
ALTER TABLE orders
    ADD COLUMN cancel_window_starts_at TIMESTAMP NULL
        COMMENT 'When the student was shown the payment confirmation; NULL falls back to paid_at';
