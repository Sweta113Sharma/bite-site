-- Indexes for the payments reconciliation screen.
--
-- payments carries only a primary key, the two Razorpay uniques, an index on order_id,
-- and the index MySQL created for the tenant_id foreign key. Every filter the admin
-- reconciliation view offers — by status, by recency — would otherwise scan the table.
--
-- Not urgent at today's volumes (single-digit rows), and deliberately added anyway: the
-- cost is nothing now and the alternative is discovering it on a table that has grown.

ALTER TABLE payments
    ADD INDEX idx_payments_status_created (status, created_at),
    ADD INDEX idx_payments_created (created_at);
