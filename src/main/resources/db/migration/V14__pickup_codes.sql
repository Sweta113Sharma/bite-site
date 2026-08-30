-- Pickup codes close the collection-dispute hole.
--
-- Until now the counter marked an order collected with a single button, and the only
-- thing tying a student to their food was a token number printed on their screen —
-- shareable in a screenshot, and no defence at all against "I never got it". The token
-- identifies the order; it does not authenticate the person holding it.
--
-- The code is generated when the kitchen marks an order ready, not at checkout, so it
-- exists only during the window it is needed and a screenshot taken earlier is useless.
-- Four digits: staff type it at a counter under time pressure, and the guess space only
-- has to outlast the few minutes an order sits on the ready shelf, against an attacker
-- who has to be physically present and is guessing at a human.

ALTER TABLE orders
    ADD COLUMN pickup_code VARCHAR(8) NULL AFTER token_no,
    ADD COLUMN pickup_code_issued_at TIMESTAMP NULL AFTER pickup_code;

-- Unique among the orders actually awaiting collection at one outlet, which is the only
-- window in which two codes could be confused. Enforced in the service rather than as a
-- partial index, which MySQL does not support.
CREATE INDEX idx_orders_pickup ON orders (outlet_id, status, pickup_code);
