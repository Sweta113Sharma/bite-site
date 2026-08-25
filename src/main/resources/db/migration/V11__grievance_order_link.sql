-- Ties a support ticket to the order it is about.
--
-- The published refund policy tells students to "include the order token" and says
-- "canteen and admin staff can see and respond to grievances tied to their outlet's
-- orders" — but grievances carried only free text, so whoever picked the ticket up had
-- no way to reach the order or its payment. Support was reading a complaint about an
-- order it could not open.
--
-- Nullable on purpose: not every ticket is about an order ("I can't verify my phone"),
-- and the existing rows predate the column.
ALTER TABLE grievances
    ADD COLUMN order_id BIGINT UNSIGNED NULL AFTER raised_by_user_id,
    ADD CONSTRAINT fk_grievances_order FOREIGN KEY (order_id) REFERENCES orders(id),
    ADD INDEX idx_grievances_order (order_id);
