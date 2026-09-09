-- Adds ADMIN: a platform administrator who runs the whole admin console but cannot
-- create, grant or revoke an elevated role (SUPER_ADMIN or ADMIN), and cannot act on an
-- account that holds one. Only a SUPER_ADMIN can do that. See RoleAssignment.
--
-- Three CHECK constraints enumerate the roles, and all three have to learn the new one or
-- the first grant fails at the database. MySQL cannot ALTER a CHECK in place, so each is
-- dropped and rebuilt under the same name — the same shape V7 and V15 used when they last
-- changed this vocabulary.
--
-- Every existing row already satisfies the new constraint, because it only ever adds a
-- value: nothing to convert, and no risk of the rebuild failing validation on live data.
--
-- Purely additive: no existing row changes, and an account only becomes an ADMIN when
-- somebody deliberately grants it.
ALTER TABLE users      DROP CONSTRAINT chk_users_role;
ALTER TABLE users ADD CONSTRAINT chk_users_role
    CHECK (role IN ('SUPER_ADMIN','ADMIN','TECH_MANAGER','CANTEEN_MANAGER','CANTEEN_OPERATOR','USER'));

ALTER TABLE users      DROP CONSTRAINT chk_users_active_role;
ALTER TABLE users ADD CONSTRAINT chk_users_active_role
    CHECK (active_role IN ('SUPER_ADMIN','ADMIN','TECH_MANAGER','CANTEEN_MANAGER','CANTEEN_OPERATOR','USER'));

ALTER TABLE user_roles DROP CONSTRAINT chk_user_roles_role;
ALTER TABLE user_roles ADD CONSTRAINT chk_user_roles_role
    CHECK (role IN ('SUPER_ADMIN','ADMIN','TECH_MANAGER','CANTEEN_MANAGER','CANTEEN_OPERATOR','USER'));
