-- Operational controls the outlet and admin panels needed before this could be run by
-- real canteens rather than demoed. Three independent additions, one migration:
--
--   1. menu_items.daily_limit   — "we can make 40 dosas today", enforced at checkout.
--   2. outlets.accepting_orders — the outlet's own temporary pause (kitchen swamped,
--      closing early). Deliberately separate from is_active, which is the admin's
--      enable/disable of the outlet as a whole: a paused outlet is still a live outlet
--      whose staff can log in and clear the queue, a deactivated one is not.
--   3. orders.cancelled_at / cancellation_reason — why an order was cancelled, kept so
--      the student can be told rather than just seeing the status flip to CANCELLED.

ALTER TABLE menu_items
    ADD COLUMN daily_limit INT NULL AFTER is_available,
    ADD CONSTRAINT chk_menu_items_daily_limit CHECK (daily_limit IS NULL OR daily_limit > 0);

ALTER TABLE outlets
    ADD COLUMN accepting_orders BOOLEAN NOT NULL DEFAULT TRUE AFTER is_active;

ALTER TABLE orders
    ADD COLUMN cancelled_at TIMESTAMP NULL AFTER completed_at,
    ADD COLUMN cancellation_reason VARCHAR(200) NULL AFTER cancelled_at;

-- Backs the "how many of this item have we sold today" roll-up that enforces daily_limit.
-- That query filters orders by outlet and created_at before joining order_items, so the
-- existing idx_orders_tenant_status doesn't help it.
ALTER TABLE orders
    ADD INDEX idx_orders_outlet_created (outlet_id, created_at);
