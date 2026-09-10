-- Money the platform can reason about: what each order actually charged, and on what terms.
--
-- THE POINT OF THE ORDER COLUMNS
-- Every rate here changes over time — a canteen renegotiates its commission, a GST rate
-- moves, the platform fee stops being free. If a settlement recomputed "3% of last month"
-- using today's 3%, every historical figure would silently rewrite itself the day terms
-- changed, and a student reopening an old order would see an invoice that is not the one
-- they paid. So each order records the terms that were applied TO IT, and nothing
-- downstream ever recalculates them.
--
-- total_amount keeps its meaning: what the student was actually charged. It is now the
-- sum of food_amount + platform_fee + tip_amount rather than food alone.
ALTER TABLE orders
    ADD COLUMN food_amount        DECIMAL(10,2) NULL COMMENT 'Items subtotal, before fee and tip',
    ADD COLUMN platform_fee       DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT 'Charged to the student; 0 when waived',
    ADD COLUMN platform_fee_shown DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT 'What the invoice struck through while free',
    ADD COLUMN tip_amount         DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT 'Voluntary contribution, never the canteen''s',
    ADD COLUMN commission_percent DECIMAL(5,2)  NULL COMMENT 'Snapshot of the rate at order time',
    ADD COLUMN commission_amount  DECIMAL(10,2) NULL COMMENT 'Frozen, never recomputed',
    ADD COLUMN gst_percent        DECIMAL(5,2)  NULL COMMENT 'Snapshot; prices are GST-inclusive';

-- Every order that already exists was food only, with no fee, tip or commission. Saying so
-- explicitly beats leaving nulls that later maths has to guess at.
UPDATE orders SET food_amount = total_amount WHERE food_amount IS NULL;

-- Settlement reads these by outlet and date, which is exactly the report's shape.
CREATE INDEX idx_orders_settlement ON orders (outlet_id, status, created_at);

-- COMMERCIAL TERMS PER CANTEEN
-- commission_percent is nullable on purpose: NULL means "use the platform default", so
-- changing the default moves every canteen that never negotiated, and leaves alone every
-- canteen that did. A default copied onto each row at onboarding could not do that.
--
-- The canteen is the registered seller and BiteSite is its agent, so the student's invoice
-- is the CANTEEN's: it carries their GSTIN and their legal name, not the platform's.
ALTER TABLE outlets
    ADD COLUMN commission_percent DECIMAL(5,2)  NULL COMMENT 'NULL = inherit the platform default',
    ADD COLUMN gstin              VARCHAR(15)   NULL COMMENT 'The seller of record on the invoice',
    ADD COLUMN legal_name         VARCHAR(200)  NULL COMMENT 'Registered name, when it differs from the display name';

-- Platform-wide defaults live in platform_settings (key/value, added in V27), so a new
-- setting never needs a migration. Seeded here so the panel opens with sane values rather
-- than a screen of blanks, and every one of them is editable.
INSERT INTO platform_settings (config_key, config_value) VALUES
    ('commission.default_percent', '0'),
    ('platform_fee.amount',        '0'),
    ('platform_fee.charge',        'false'),
    ('platform_fee.show_when_free','true'),
    ('platform_fee.label',         'Platform fee'),
    ('gst.show_breakdown',         'false'),
    ('gst.percent',                '5'),
    ('gst.note',                   'Prices are inclusive of all applicable taxes.'),
    ('tip.enabled',                'false'),
    ('tip.title',                  'Buy the developer a coffee'),
    ('tip.message',                'BiteSite is run by one person. A small tip keeps the servers on. Entirely optional.'),
    ('tip.amounts',                '5,10,20'),
    ('invoice.footer',             'Thank you for ordering with BiteSite.')
ON DUPLICATE KEY UPDATE config_key = config_key;
