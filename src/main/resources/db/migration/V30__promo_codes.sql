-- Order-level promo codes.
--
-- THE COLUMN THAT MATTERS MOST IS funded_by.
-- A discount has to come out of somebody's pocket, and which pocket changes the payout:
--
--   CANTEEN-funded  the canteen chose to discount its own food, so its revenue really is
--                   lower and commission is taken on what was actually charged.
--   PLATFORM-funded the platform is buying the student's goodwill. The canteen sold at
--                   full price and must be paid at full price; the platform absorbs the
--                   difference out of its own margin.
--
-- Without this distinction a platform-funded promotion would quietly be paid for by the
-- canteens, which is the kind of thing that ends a relationship with a college.
CREATE TABLE promo_codes (
    id                BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    code              VARCHAR(32)    NOT NULL,
    description       VARCHAR(200)   NULL,
    discount_type     VARCHAR(10)    NOT NULL,
    discount_value    DECIMAL(10,2)  NOT NULL,
    -- A percentage without a ceiling is an open cheque on a large order.
    max_discount      DECIMAL(10,2)  NULL,
    min_order_value   DECIMAL(10,2)  NULL,
    funded_by         VARCHAR(10)    NOT NULL,
    -- Scope: null tenant means every college, null outlet means every canteen in scope.
    tenant_id         BIGINT UNSIGNED NULL,
    outlet_id         BIGINT UNSIGNED NULL,
    valid_from        TIMESTAMP      NULL,
    valid_until       TIMESTAMP      NULL,
    max_redemptions   INT            NULL,
    max_per_user      INT            NULL,
    is_active         BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_promo_code UNIQUE (code),
    CONSTRAINT fk_promo_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_promo_outlet FOREIGN KEY (outlet_id) REFERENCES outlets(id),
    CONSTRAINT chk_promo_type   CHECK (discount_type IN ('FLAT','PERCENT')),
    CONSTRAINT chk_promo_funder CHECK (funded_by IN ('PLATFORM','CANTEEN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One row per use. The redemption count is derived from these rather than kept as a
-- counter on promo_codes: a counter and a set of rows drift apart the first time
-- something fails halfway, and then nobody can say which one is right.
--
-- UNIQUE on order_id enforces one code per order at the database, not just in a service.
CREATE TABLE promo_redemptions (
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    promo_code_id   BIGINT UNSIGNED NOT NULL,
    order_id        BIGINT UNSIGNED NOT NULL,
    user_id         BIGINT UNSIGNED NOT NULL,
    discount_amount DECIMAL(10,2)   NOT NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_promo_redemption_order UNIQUE (order_id),
    CONSTRAINT fk_redemption_promo FOREIGN KEY (promo_code_id) REFERENCES promo_codes(id),
    CONSTRAINT fk_redemption_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_redemption_user  FOREIGN KEY (user_id)  REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_redemptions_user ON promo_redemptions (promo_code_id, user_id);

-- Snapshotted onto the order like every other commercial term, so an invoice and a
-- payout always show the discount that was actually applied even if the code is later
-- edited or deleted.
ALTER TABLE orders
    ADD COLUMN promo_code         VARCHAR(32)   NULL,
    ADD COLUMN discount_amount    DECIMAL(10,2) NOT NULL DEFAULT 0,
    ADD COLUMN discount_funded_by VARCHAR(10)   NULL;
