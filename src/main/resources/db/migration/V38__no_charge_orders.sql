-- Mark orders that were placed without taking any money.
--
-- V36 added the Play review account, whose checkout skips Razorpay and records a CAPTURED
-- payment with an invented id (play_review_payment_<order id>). Nothing told those orders
-- apart from real ones afterwards, so they counted as revenue in the admin dashboard and
-- analytics, as sales on the canteen's report, and as money owed in settlement; and
-- cancelling one called Razorpay to refund a payment Razorpay has never heard of.
--
-- no_charge is set at checkout from here on. Every query that sums money filters on it;
-- queue, status and daily-cap queries do not, because the order really is in the kitchen.
--
-- Added at the end of the table with a constant default, which MySQL 8 applies as an
-- INSTANT change: no table copy on the production database.

ALTER TABLE orders
    ADD COLUMN no_charge BOOLEAN NOT NULL DEFAULT FALSE;

-- Review orders already placed. The review checkout is the only writer of this prefix,
-- and Razorpay order ids start with order_, so the match cannot catch a real payment.
UPDATE orders o
JOIN payments p ON p.order_id = o.id
SET o.no_charge = TRUE
WHERE p.razorpay_order_id LIKE 'play\_review\_order\_%';
