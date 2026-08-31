-- Menu categories become a real table.
--
-- category was a free-text VARCHAR on every menu item, so "Snacks", "snacks" and "Snack"
-- were three different sections on the student menu, renaming a category meant editing
-- every item in it, and ordering was alphabetical whether or not that made sense — drinks
-- before mains because D sorts before M.
--
-- The seed inserts category strings at V2, long before this runs, so those rows convert
-- like any other. Seeds are never edited: their Flyway checksums are locked.

CREATE TABLE categories (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    outlet_id BIGINT UNSIGNED NOT NULL,
    name VARCHAR(80) NOT NULL,
    -- Explicit display order. Alphabetical is not what a menu wants: a canteen puts its
    -- mains before its drinks regardless of spelling.
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_categories_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_categories_outlet FOREIGN KEY (outlet_id) REFERENCES outlets(id),
    -- Scoped per outlet, not per tenant: two canteens at one college each keep their own
    -- "Snacks" rather than being forced to share one.
    CONSTRAINT uq_categories_outlet_name UNIQUE (outlet_id, name),
    INDEX idx_categories_outlet (outlet_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Backfill: one row per distinct (outlet, category) actually in use. TRIM collapses
-- accidental whitespace variants; the UNIQUE above then absorbs the duplicates that
-- differ only by case, since the column collation is case-insensitive.
INSERT IGNORE INTO categories (tenant_id, outlet_id, name, sort_order)
SELECT DISTINCT tenant_id, outlet_id, TRIM(category), 0
FROM menu_items
WHERE category IS NOT NULL AND TRIM(category) <> '';

ALTER TABLE menu_items
    ADD COLUMN category_id BIGINT UNSIGNED NULL AFTER category;

UPDATE menu_items mi
JOIN categories c ON c.outlet_id = mi.outlet_id AND c.name = TRIM(mi.category)
SET mi.category_id = c.id;

-- Any item whose category was blank gets an explicit home rather than a null, so the
-- student menu never has to render an unnamed section.
INSERT IGNORE INTO categories (tenant_id, outlet_id, name, sort_order)
SELECT DISTINCT tenant_id, outlet_id, 'Uncategorised', 999
FROM menu_items WHERE category_id IS NULL;

UPDATE menu_items mi
JOIN categories c ON c.outlet_id = mi.outlet_id AND c.name = 'Uncategorised'
SET mi.category_id = c.id
WHERE mi.category_id IS NULL;

-- Only now that every row has one can the column be required.
ALTER TABLE menu_items
    MODIFY COLUMN category_id BIGINT UNSIGNED NOT NULL,
    ADD CONSTRAINT fk_menu_items_category FOREIGN KEY (category_id) REFERENCES categories(id);

-- The old free-text column goes. MenuItem keeps a `category` field, but it is now the
-- joined category name — read-only, and the illustration matching and student grouping
-- that depend on it carry on working unchanged.
ALTER TABLE menu_items DROP COLUMN category;
