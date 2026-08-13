-- Seeds two demo colleges so login-based tenant switching is provable immediately.
-- Which college a user sees is determined entirely by their account (users.tenant_id),
-- not by any subdomain or URL — every seeded account below shares the password
-- "Demo@12345" (BCrypt hash below). Change before using this anywhere but local dev.

INSERT INTO tenants (name, status) VALUES
    ('Demo College', 'ACTIVE'),
    ('Second College', 'ACTIVE');

INSERT INTO outlets (tenant_id, name, is_active)
SELECT id, 'Main Canteen', TRUE FROM tenants WHERE name = 'Demo College';

INSERT INTO outlets (tenant_id, name, is_active)
SELECT id, 'North Block Cafe', TRUE FROM tenants WHERE name = 'Second College';

-- Platform-level accounts (no tenant_id): not tied to any single college.
INSERT INTO users (tenant_id, outlet_id, name, email, password_hash, role, is_active) VALUES
    (NULL, NULL, 'BiteSite Super Admin', 'admin@bitesite.local',
     '$2a$10$07vPJw9xPj1bpyhkEzGV6.M53oAapcZ7LNboT1QL.lVBoxW4YymyK', 'SUPER_ADMIN', TRUE),
    (NULL, NULL, 'BiteSite Tech Manager', 'tech@bitesite.local',
     '$2a$10$07vPJw9xPj1bpyhkEzGV6.M53oAapcZ7LNboT1QL.lVBoxW4YymyK', 'TECH_MANAGER', TRUE);

-- Demo College accounts.
INSERT INTO users (tenant_id, outlet_id, name, email, password_hash, role, is_active)
SELECT t.id, o.id, 'Demo Canteen Staff', 'canteen@demo.local',
       '$2a$10$07vPJw9xPj1bpyhkEzGV6.M53oAapcZ7LNboT1QL.lVBoxW4YymyK', 'CANTEEN_STAFF', TRUE
FROM tenants t JOIN outlets o ON o.tenant_id = t.id
WHERE t.name = 'Demo College';

INSERT INTO users (tenant_id, outlet_id, name, email, password_hash, phone, roll_no, role, is_active)
SELECT id, NULL, 'Demo Student', 'student@demo.local',
       '$2a$10$07vPJw9xPj1bpyhkEzGV6.M53oAapcZ7LNboT1QL.lVBoxW4YymyK', '9999999999', 'CSE-101',
       'STUDENT', TRUE
FROM tenants WHERE name = 'Demo College';

-- Second College accounts — same password, completely separate data.
INSERT INTO users (tenant_id, outlet_id, name, email, password_hash, role, is_active)
SELECT t.id, o.id, 'Second College Canteen Staff', 'canteen@second.local',
       '$2a$10$07vPJw9xPj1bpyhkEzGV6.M53oAapcZ7LNboT1QL.lVBoxW4YymyK', 'CANTEEN_STAFF', TRUE
FROM tenants t JOIN outlets o ON o.tenant_id = t.id
WHERE t.name = 'Second College';

INSERT INTO users (tenant_id, outlet_id, name, email, password_hash, phone, roll_no, role, is_active)
SELECT id, NULL, 'Second College Student', 'student@second.local',
       '$2a$10$07vPJw9xPj1bpyhkEzGV6.M53oAapcZ7LNboT1QL.lVBoxW4YymyK', '8888888888', 'ECE-202',
       'STUDENT', TRUE
FROM tenants WHERE name = 'Second College';

INSERT INTO menu_items (tenant_id, outlet_id, name, category, price, discount_percent, is_available)
SELECT t.id, o.id, seed_items.name, seed_items.category, seed_items.price, seed_items.discount_percent, TRUE
FROM tenants t
JOIN outlets o ON o.tenant_id = t.id
CROSS JOIN (
    SELECT 'Veg Sandwich' AS name, 'Snacks' AS category, 60.00 AS price, NULL AS discount_percent
    UNION ALL SELECT 'Samosa (2 pcs)', 'Snacks', 30.00, 10.00
    UNION ALL SELECT 'Masala Dosa', 'Meals', 80.00, NULL
    UNION ALL SELECT 'Veg Thali', 'Meals', 120.00, NULL
    UNION ALL SELECT 'Cold Coffee', 'Beverages', 50.00, NULL
    UNION ALL SELECT 'Masala Chai', 'Beverages', 20.00, NULL
) AS seed_items
WHERE t.name = 'Demo College';

INSERT INTO menu_items (tenant_id, outlet_id, name, category, price, is_available)
SELECT t.id, o.id, seed_items.name, seed_items.category, seed_items.price, TRUE
FROM tenants t
JOIN outlets o ON o.tenant_id = t.id
CROSS JOIN (
    SELECT 'Chicken Roll' AS name, 'Snacks' AS category, 90.00 AS price
    UNION ALL SELECT 'Paneer Roll', 'Snacks', 80.00
    UNION ALL SELECT 'Fried Rice', 'Meals', 100.00
    UNION ALL SELECT 'Lemon Tea', 'Beverages', 25.00
) AS seed_items
WHERE t.name = 'Second College';
