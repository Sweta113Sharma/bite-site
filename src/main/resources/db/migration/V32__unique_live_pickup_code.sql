-- Stop two live orders at one counter sharing a pickup code.
--
-- THE BUG
-- issuePickupCode read the codes currently live at an outlet, picked one that was not in
-- that set, and wrote it. Nothing sat between the read and the write, so two orders being
-- marked ready in the same instant could both read the same set, both pick the same free
-- code, and both keep it. Nothing in the schema objected: idx_orders_pickup is not unique.
--
-- The pickup code is the authentication event for handover — "the code, not the token, is
-- what authenticates collection". Two live orders sharing one at the same counter means a
-- student can be handed somebody else's food.
--
-- Found by a concurrency test, not in the wild: 4 collisions out of ~1200 orders driven
-- through the lifecycle at once. It needs simultaneity to happen, which is why it has
-- never been seen, and which is also why it will eventually be seen at a busy counter.
--
-- THE FIX, copied from the token_day pattern in V13
-- A generated column that holds the pickup code ONLY while the order is actually on the
-- ready shelf, and NULL otherwise. MySQL lets NULLs repeat in a unique index, so orders
-- that are not awaiting collection do not compete for codes — which preserves exactly the
-- rule the application intended: uniqueness among live orders at one outlet, and a repeat
-- across outlets or across days is meaningless.
--
-- Because the column is STORED and derived from status, it clears itself the moment an
-- order is collected, freeing that code again with no extra write.

-- Deduplicate first, or adding the index below fails and takes the deploy with it.
-- Keeps the oldest of each colliding group and clears the rest, so at most one student per
-- group is asked for a code again. Expected to affect zero rows in production: the race
-- needs two staff marking orders ready in the same instant at the same outlet.
UPDATE orders o
    JOIN (
        SELECT id FROM (
            SELECT id,
                   ROW_NUMBER() OVER (PARTITION BY outlet_id, pickup_code ORDER BY id) AS rn
            FROM orders
            WHERE status = 'READY_FOR_PICKUP' AND pickup_code IS NOT NULL
        ) ranked
        WHERE ranked.rn > 1
    ) dupes ON dupes.id = o.id
SET o.pickup_code = NULL;

ALTER TABLE orders
    ADD COLUMN active_pickup_code VARCHAR(10)
        GENERATED ALWAYS AS (IF(status = 'READY_FOR_PICKUP', pickup_code, NULL)) STORED
        COMMENT 'pickup_code while awaiting collection, NULL otherwise; carries the uniqueness rule';

ALTER TABLE orders
    ADD CONSTRAINT uq_orders_active_pickup_code UNIQUE (outlet_id, active_pickup_code);
