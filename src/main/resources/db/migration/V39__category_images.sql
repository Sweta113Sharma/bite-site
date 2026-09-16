-- Artwork for a menu category, so the category rail stops borrowing the first dish's photo.
--
-- Until now the student menu's category chips rendered `items.get(0).photoPath` — whatever
-- happened to be first in that category. A canteen that photographed one samosa got a
-- samosa as the face of "Snacks", and a canteen that photographed nothing got a generic
-- glyph. Neither is a decision anybody made.
--
-- Two tables rather than one, because the two things being stored are genuinely different
-- shapes and merging them would mean a row where half the columns are always NULL:
--
--   category_images          one outlet's own picture for one of ITS categories. Keyed by
--                            category_id, which is a real foreign key, so the image dies
--                            with the category and cannot outlive it as an orphan.
--
--   category_default_images  the platform's picture for a category NAME, set by an admin
--                            and shared by every outlet that happens to use that name.
--                            Cannot be keyed on category_id: "Snacks" is a different row
--                            in `categories` for every outlet (see uq_categories_outlet_name),
--                            and the whole point of a default is that one upload covers
--                            all of them.
--
-- Resolution order at render time is outlet's own, then the platform default, then the
-- behaviour that exists today (first item's photo, then the illustration). Each rung only
-- applies when the one above it is absent, so adding this changes nothing for a canteen
-- that never uploads anything.

CREATE TABLE category_images (
    -- The category IS the key. One image per category, enforced by the schema rather than
    -- by an application check, and ON DELETE CASCADE means deleting a category takes its
    -- image with it instead of leaving a row pointing at nothing.
    category_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,

    -- Denormalised from `categories` on purpose. Every DAO method in this codebase takes an
    -- explicit tenantId and filters on it (see CLAUDE.md); carrying the column here means
    -- the tenant check is on the row being read rather than on a joined table, which is
    -- both cheaper and harder to forget.
    tenant_id BIGINT UNSIGNED NOT NULL,
    outlet_id BIGINT UNSIGNED NOT NULL,

    -- A resolvable path or URL exactly as FileStorageService returned it, rendered as-is —
    -- same contract as tenants.logo_path and outlets.logo_path.
    image_path VARCHAR(500) NOT NULL,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_category_images_category FOREIGN KEY (category_id)
        REFERENCES categories(id) ON DELETE CASCADE,
    CONSTRAINT fk_category_images_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_category_images_outlet FOREIGN KEY (outlet_id) REFERENCES outlets(id),

    -- The menu screen's only read: every image for one outlet, in one indexed hit.
    INDEX idx_category_images_outlet (outlet_id, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


CREATE TABLE category_default_images (
    -- The category name, lowercased and trimmed, so "Snacks", "snacks" and " Snacks "
    -- are one default rather than three. Normalisation happens in Java on the way in AND
    -- on the way out (CategoryImageService.normalise) so the two can never disagree.
    --
    -- 80 chars matches categories.name. As a utf8mb4 primary key that is 320 bytes, well
    -- inside InnoDB's 3072-byte index limit.
    name_key VARCHAR(80) NOT NULL PRIMARY KEY,

    -- The name as a human first typed it, kept only so the admin screen can list
    -- "Cold Beverages" instead of "cold beverages". Never used for matching.
    display_name VARCHAR(80) NOT NULL,

    image_path VARCHAR(500) NOT NULL,

    -- Which admin set it, for the audit trail. Nullable because the account may later be
    -- deleted under the privacy flow, and losing the attribution should not lose the image.
    uploaded_by BIGINT UNSIGNED NULL,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_category_default_images_user FOREIGN KEY (uploaded_by)
        REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
