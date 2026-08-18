-- Multi-role RBAC refactor + STUDENT → USER rename
-- 1. Rename the STUDENT role to USER everywhere
-- 2. Add user_roles join table for multi-role support
-- 3. Add active_role column to users (the "view-mode")
-- 4. Re-add CHECK constraints (same role vocabulary the app already had)
-- 5. Add role_audit table for grant/revoke tracking

-- ============================================================
-- Step 1: Rename STUDENT → USER in existing data
-- ============================================================

-- Temporarily drop the CHECK so we can update values
ALTER TABLE users DROP CONSTRAINT chk_users_role;

UPDATE users SET role = 'USER' WHERE role = 'STUDENT';

-- ============================================================
-- Step 2: Add active_role column (the "view-mode")
-- ============================================================

ALTER TABLE users ADD COLUMN active_role VARCHAR(20) NULL AFTER role;
UPDATE users SET active_role = role;
ALTER TABLE users MODIFY active_role VARCHAR(20) NOT NULL;

-- ============================================================
-- Step 3: Re-add CHECK constraints with full role vocabulary
-- ============================================================

ALTER TABLE users ADD CONSTRAINT chk_users_role CHECK (role IN (
    'SUPER_ADMIN','TECH_MANAGER','CANTEEN_STAFF','USER'
));
ALTER TABLE users ADD CONSTRAINT chk_users_active_role CHECK (active_role IN (
    'SUPER_ADMIN','TECH_MANAGER','CANTEEN_STAFF','USER'
));

-- ============================================================
-- Step 4: user_roles join table (source of truth for entitlements)
-- ============================================================

CREATE TABLE user_roles (
    user_id    BIGINT UNSIGNED NOT NULL,
    role       VARCHAR(20) NOT NULL,
    granted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    granted_by BIGINT UNSIGNED NULL,
    PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_user_roles_granted_by FOREIGN KEY (granted_by) REFERENCES users(id),
    CONSTRAINT chk_user_roles_role CHECK (role IN (
        'SUPER_ADMIN','TECH_MANAGER','CANTEEN_STAFF','USER'
    ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Migrate existing single role into user_roles
INSERT INTO user_roles (user_id, role)
SELECT id, role FROM users;

-- ============================================================
-- Step 5: role_audit table (accountability for grants/revokes)
-- ============================================================

CREATE TABLE role_audit (
    id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT UNSIGNED NOT NULL,
    role          VARCHAR(20) NOT NULL,
    action        VARCHAR(10) NOT NULL,
    actor_user_id BIGINT UNSIGNED NULL,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_role_audit_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_role_audit_actor FOREIGN KEY (actor_user_id) REFERENCES users(id),
    CONSTRAINT chk_role_audit_action CHECK (action IN ('GRANT','REVOKE')),
    INDEX idx_role_audit_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
