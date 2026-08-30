-- Demo accounts for the manager/operator split. Seed path only.
--
-- This lives in db/seed, not db/migration, for the reason application.yml spells out: a
-- production deploy must never receive demo accounts sharing one publicly documented
-- password. Local dev and the test profile opt in via FLYWAY_LOCATIONS.
--
-- V15 has already converted the seeded canteen accounts to CANTEEN_MANAGER. What is
-- missing is anyone to test the restriction against, so this adds an operator per
-- college, and grants the platform superuser the operator role as well so the outlet
-- portal's role switcher has two entries to switch between.

-- Password is Demo@12345, the same bcrypt hash every other demo account uses.
INSERT INTO users (tenant_id, outlet_id, name, email, password_hash, role, active_role, is_active)
SELECT t.id, o.id, 'Demo Operator', 'operator@demo.local',
       '$2a$10$07vPJw9xPj1bpyhkEzGV6.M53oAapcZ7LNboT1QL.lVBoxW4YymyK',
       'CANTEEN_OPERATOR', 'CANTEEN_OPERATOR', TRUE
FROM tenants t
JOIN outlets o ON o.tenant_id = t.id
WHERE t.name = 'Demo College'
LIMIT 1;

INSERT IGNORE INTO user_roles (user_id, role, granted_by)
SELECT id, 'CANTEEN_OPERATOR', NULL FROM users WHERE email = 'operator@demo.local';

-- The superuser already holds CANTEEN_MANAGER (converted by V15 from CANTEEN_STAFF).
-- Adding the operator grant gives it both outlet roles, which is what exercises the
-- declaration-order rule in Role.java: holding both, it must log in as the manager.
INSERT IGNORE INTO user_roles (user_id, role, granted_by)
SELECT id, 'CANTEEN_OPERATOR', NULL FROM users WHERE email = 'superuser@bitesite.local';
