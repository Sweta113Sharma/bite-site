-- Update seed data for multi-role RBAC
-- 1. Set active_role for all existing users (V7 already did this generically)
-- 2. Create a superuser account with ALL roles for dev/testing

-- Superuser: holds every role, so you can test role-switching across all portals.
-- Scoped to Demo College / Main Canteen (not NULL like the platform-only SUPER_ADMIN/
-- TECH_MANAGER seed accounts) so that switching into USER or CANTEEN_STAFF actually has
-- a real college/outlet to show data for, instead of an empty screen.
-- Password: Demo@12345 (same BCrypt hash as all other seed accounts)
INSERT INTO users (tenant_id, outlet_id, name, email, password_hash, role, active_role, is_active, email_verified)
SELECT t.id, o.id, 'BiteSite Superuser', 'superuser@bitesite.local',
       '$2a$10$07vPJw9xPj1bpyhkEzGV6.M53oAapcZ7LNboT1QL.lVBoxW4YymyK',
       'SUPER_ADMIN', 'SUPER_ADMIN', TRUE, TRUE
FROM tenants t JOIN outlets o ON o.tenant_id = t.id
WHERE t.name = 'Demo College' AND o.name = 'Main Canteen';

-- Grant ALL roles to the superuser
INSERT INTO user_roles (user_id, role)
SELECT u.id, r.role_name
FROM users u
CROSS JOIN (
    SELECT 'SUPER_ADMIN' AS role_name
    UNION ALL SELECT 'TECH_MANAGER'
    UNION ALL SELECT 'CANTEEN_STAFF'
    UNION ALL SELECT 'USER'
) r
WHERE u.email = 'superuser@bitesite.local';

-- Record the grants in role_audit
INSERT INTO role_audit (user_id, role, action, actor_user_id)
SELECT u.id, r.role_name, 'GRANT', NULL
FROM users u
CROSS JOIN (
    SELECT 'SUPER_ADMIN' AS role_name
    UNION ALL SELECT 'TECH_MANAGER'
    UNION ALL SELECT 'CANTEEN_STAFF'
    UNION ALL SELECT 'USER'
) r
WHERE u.email = 'superuser@bitesite.local';
