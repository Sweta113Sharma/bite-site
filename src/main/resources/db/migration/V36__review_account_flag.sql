-- Add a flag to designate accounts used by Google Play reviewers.
-- Orders placed by these accounts are auto-advanced through the happy path
-- (PAID → PREPARING → READY_FOR_PICKUP → COMPLETED) by a scheduled job,
-- so the reviewer can verify the full lifecycle without canteen staff.
ALTER TABLE users ADD COLUMN review_account BOOLEAN NOT NULL DEFAULT FALSE;
