-- Splits the single CANTEEN_STAFF role into CANTEEN_MANAGER and CANTEEN_OPERATOR.
--
-- One undifferentiated outlet role meant whoever could work the queue could also rewrite
-- prices and delete the menu. Worse, the outlet portal has no per-method guards at all —
-- the URL rule in SecurityConfig was the only thing standing between an authenticated
-- counter account and menu deletion. Splitting the role is what makes the audit log
-- meaningful: "who changed this price" stops having the same answer as "who was on shift".
--
-- Existing rows become CANTEEN_MANAGER. They can already do everything, so nothing anyone
-- could do yesterday stops working; operators are created deliberately from here on.
--
-- Ordering is forced. MySQL 8.0.16+ enforces CHECK on UPDATE, so converting the data
-- first would be rejected by the very constraint being replaced. Constraints come off,
-- then rows change, then the new vocabulary goes on.
--
-- The seed files are deliberately untouched. db/seed/V2 and V8 insert 'CANTEEN_STAFF' and
-- have locked Flyway checksums — editing them fails validation on any database that has
-- already run them. On a fresh database the chain still works: V1 admits the old value,
-- V2 inserts it, V7 re-admits it, V8 inserts more, and this migration converts the lot
-- last. In production, where the seed path is excluded, these UPDATEs are no-ops.

-- Step 1 — drop the three CHECKs that pin the role vocabulary.
ALTER TABLE users      DROP CONSTRAINT chk_users_role;
ALTER TABLE users      DROP CONSTRAINT chk_users_active_role;
ALTER TABLE user_roles DROP CONSTRAINT chk_user_roles_role;

-- Step 2 — convert existing rows.
UPDATE users SET role        = 'CANTEEN_MANAGER' WHERE role        = 'CANTEEN_STAFF';
UPDATE users SET active_role = 'CANTEEN_MANAGER' WHERE active_role = 'CANTEEN_STAFF';

-- user_roles is keyed on (user_id, role), so a plain UPDATE would collide for anyone who
-- somehow already held CANTEEN_MANAGER. IGNORE skips those; the DELETE clears whatever
-- the UPDATE then left behind. No such row exists today — this is belt and braces.
UPDATE IGNORE user_roles SET role = 'CANTEEN_MANAGER' WHERE role = 'CANTEEN_STAFF';
DELETE FROM user_roles WHERE role = 'CANTEEN_STAFF';

-- role_audit deliberately keeps its history. Its `role` column has no CHECK constraint
-- and is never read back into the Java enum, so old GRANT/REVOKE rows naming
-- CANTEEN_STAFF stay as an accurate record of what happened at the time.

-- Step 3 — re-add the CHECKs with the new vocabulary.
ALTER TABLE users ADD CONSTRAINT chk_users_role CHECK (role IN (
    'SUPER_ADMIN','TECH_MANAGER','CANTEEN_MANAGER','CANTEEN_OPERATOR','USER'));
ALTER TABLE users ADD CONSTRAINT chk_users_active_role CHECK (active_role IN (
    'SUPER_ADMIN','TECH_MANAGER','CANTEEN_MANAGER','CANTEEN_OPERATOR','USER'));
ALTER TABLE user_roles ADD CONSTRAINT chk_user_roles_role CHECK (role IN (
    'SUPER_ADMIN','TECH_MANAGER','CANTEEN_MANAGER','CANTEEN_OPERATOR','USER'));

-- Step 4 — clear persisted sessions.
--
-- Easy to miss and it breaks login hard. User is Serializable and lives inside the
-- SecurityContext that Spring Session JDBC stores as a blob, so any session written
-- before this migration carries a serialized Role.CANTEEN_STAFF. Deserializing an enum
-- constant that no longer exists throws rather than degrading, so those sessions would
-- fail on their next request. Children first, for the foreign key.
DELETE FROM SPRING_SESSION_ATTRIBUTES;
DELETE FROM SPRING_SESSION;
