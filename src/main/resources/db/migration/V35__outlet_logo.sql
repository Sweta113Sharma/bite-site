-- A canteen's own logo.
--
-- Until now the only brand mark in the app was the college's (tenants.logo_path), set by
-- the platform admin. A canteen had no way to look like itself: the student picker showed
-- every outlet with the same storefront glyph, and the menu hero wore the platform sticker.
-- Managers set this themselves from Outlet settings; students see it on the picker card
-- and in the menu hero.
--
-- Same shape as tenants.logo_path: a resolvable path or URL that FileStorageService
-- returned, rendered as-is. Null means "no logo", which falls back to the glyph.

ALTER TABLE outlets
    ADD COLUMN logo_path VARCHAR(500) NULL AFTER notice;
