-- Let the kitchen take unavailable items off a paid order, refund just those, and mark
-- them out of stock for the rest of the day.
--
-- Until now an order was all or nothing: one missing item meant cancelling and refunding
-- the whole order, or cooking four of five and leaving the student to chase the fifth
-- through Support. Every refund was a full refund, and the reconciliation code relied on
-- that (it matched refunds on "equals the captured amount").
--
-- order_refunds       one row per partial refund. Written and COMMITTED before Razorpay
--                     is called, in the same transaction that takes the lines off the
--                     order, for the same reason REFUND_PENDING exists (V33): a refund can
--                     succeed while the HTTP call reports failure, so the record of asking
--                     has to outlive the call. gateway_refund_id is how the refund.processed
--                     webhook finds the row again.
--
-- payments.refunded_amount
--                     the part of this capture already claimed by partial refunds. A later
--                     full cancellation refunds amount - refunded_amount, never the original
--                     amount, which Razorpay would refuse anyway once part has gone back.
--
-- order_items.cancelled_at / cancellation_reason / refund_id
--                     the line stays on the order so the student and the kitchen can see
--                     what was taken off and why. Everything that counts sold food ignores
--                     cancelled lines.
--
-- menu_items.out_of_stock_on
--                     "out of stock today". The item is off sale only while this equals the
--                     database's CURDATE(), which is the same day boundary the daily sales
--                     cap already uses, so it comes back on sale by itself tomorrow with no
--                     job to run. is_available stays the manual, until-further-notice switch.

CREATE TABLE order_refunds (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    order_id BIGINT UNSIGNED NOT NULL,
    payment_id BIGINT UNSIGNED NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reason VARCHAR(200) NOT NULL,
    gateway_refund_id VARCHAR(64) NULL,
    requested_by BIGINT UNSIGNED NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    settled_at TIMESTAMP NULL,
    CONSTRAINT fk_order_refunds_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_order_refunds_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_order_refunds_payment FOREIGN KEY (payment_id) REFERENCES payments(id),
    CONSTRAINT fk_order_refunds_requested_by FOREIGN KEY (requested_by) REFERENCES users(id),
    CONSTRAINT uq_order_refunds_gateway_refund UNIQUE (gateway_refund_id),
    CONSTRAINT chk_order_refunds_status CHECK (status IN ('PENDING','REFUNDED','FAILED')),
    CONSTRAINT chk_order_refunds_amount CHECK (amount > 0),
    INDEX idx_order_refunds_order (order_id),
    INDEX idx_order_refunds_payment_status (payment_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE payments
    ADD COLUMN refunded_amount DECIMAL(10,2) NOT NULL DEFAULT 0 AFTER amount,
    ADD CONSTRAINT chk_payments_refunded_amount CHECK (refunded_amount >= 0 AND refunded_amount <= amount);

ALTER TABLE order_items
    ADD COLUMN cancelled_at TIMESTAMP NULL,
    ADD COLUMN cancellation_reason VARCHAR(200) NULL,
    ADD COLUMN refund_id BIGINT UNSIGNED NULL,
    ADD CONSTRAINT fk_order_items_refund FOREIGN KEY (refund_id) REFERENCES order_refunds(id);

ALTER TABLE menu_items
    ADD COLUMN out_of_stock_on DATE NULL AFTER is_available;
