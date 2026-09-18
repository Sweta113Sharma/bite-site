-- A canteen's own picture for the "All Dishes" chip.
--
-- "All Dishes" is not a category: it is the first chip on the student menu, the one that
-- clears the filter, and it has no row in `categories`. So category_images (keyed by a
-- real category_id, with a foreign key to it) has nowhere to put it, and the chip was
-- hard-coded to the bundled ramen illustration with no way for anyone to change it.
--
-- One picture per outlet, so it lives on the outlet row, same shape as outlets.logo_path:
-- a resolvable path or URL that FileStorageService returned, rendered as-is.
--
-- Resolution mirrors the real categories: this column, then the platform default an admin
-- sets (platform_settings key 'category_image.all_dishes', no schema needed), then the
-- ramen. NULL here means "not set", which changes nothing for a canteen that never uploads.

ALTER TABLE outlets
    ADD COLUMN all_dishes_image_path VARCHAR(500) NULL AFTER logo_path;
