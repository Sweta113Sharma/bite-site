-- Indexes for the admin dashboard's insight queries (sell-out alerts, popular items
-- today, peak hours). All three aggregate order_items joined to today's orders, and the
-- existing indexes cover neither side of that join.
ALTER TABLE order_items
    ADD INDEX idx_order_items_menu_item (menu_item_id);

ALTER TABLE orders
    ADD INDEX idx_orders_token_day (token_day);
