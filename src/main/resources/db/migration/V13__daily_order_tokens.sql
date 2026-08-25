-- Order tokens run out.
--
-- generateUniqueToken() picks from BITE-1000..BITE-9999 — 9,000 values — and
-- uq_orders_token made them unique per tenant for all time, with no purge of old orders
-- anywhere in the app. So the pool only ever shrank. Ten attempts against a tenant
-- holding N tokens fails with probability (N/9000)^10: about 1 checkout in 3 at 8,100
-- orders, and every checkout once 9,000 is reached, at which point that college can
-- never place another order. At 300 orders a day that is roughly a month.
--
-- A token only has to be unambiguous among the orders a counter is actually calling out,
-- which is today's. Scoping uniqueness to the day makes the pool refill every midnight
-- and turns 9,000-forever into 9,000-per-day, which no single canteen will approach.
--
-- token_day is generated from created_at rather than set by the application so the value
-- the constraint is enforced on can never drift from the timestamp it is derived from.

ALTER TABLE orders
    ADD COLUMN token_day DATE GENERATED ALWAYS AS (DATE(created_at)) STORED AFTER token_no;

-- The old constraint is strictly tighter than the new one, so every existing row already
-- satisfies what replaces it and this needs no data fix-up.
ALTER TABLE orders DROP INDEX uq_orders_token;

ALTER TABLE orders
    ADD CONSTRAINT uq_orders_token_day UNIQUE (tenant_id, token_day, token_no);
