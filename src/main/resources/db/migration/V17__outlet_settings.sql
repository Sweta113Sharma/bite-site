-- Outlet profile: opening hours and a contact line.
--
-- Students currently have no way to know when a canteen is open, so an order placed at
-- 21:00 looks exactly like one placed at noon. The outlet's accepting_orders switch
-- (V12) covers "we are closed right now" as a manual act; these columns are the stated
-- schedule, which is what a student needs before they tap.
--
-- Nullable throughout: an outlet that has not set its hours is not misconfigured, it just
-- has nothing to display. The app treats null as "no stated hours" rather than as midnight.
--
-- TIME rather than TIMESTAMP on purpose. These are wall-clock times of day that repeat, not
-- instants, so they carry none of the timezone conversion hazard that TIMESTAMP columns do
-- in this schema (see V13 and the notes in OrderDao).

ALTER TABLE outlets
    ADD COLUMN opens_at TIME NULL AFTER accepting_orders,
    ADD COLUMN closes_at TIME NULL AFTER opens_at,
    ADD COLUMN contact_phone VARCHAR(20) NULL AFTER closes_at,
    ADD COLUMN notice VARCHAR(200) NULL AFTER contact_phone;
