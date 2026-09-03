-- Changing the email you sign in with.
--
-- Name, phone and roll number became editable in the account screen; email deliberately
-- did not. It is the login identifier and carries a UNIQUE constraint, so swapping it the
-- way the other fields are swapped would let someone type an address they do not own and
-- lock themselves out of their own account — or, worse, park a claim on someone else's.
--
-- So the new address is staged here and only becomes the account's email once a code sent
-- TO THAT ADDRESS has been entered. Until then the old one still signs in, unchanged.
--
-- pending_email is NOT unique. Two people may both stage the same address; whoever proves
-- it first gets it, and the uniqueness constraint on users.email is what actually decides
-- that — enforced at the moment of the swap, not at the moment of the request. Making this
-- column unique instead would let anyone reserve an address they cannot prove, simply by
-- typing it, which is a denial-of-service on somebody else's signup.
ALTER TABLE users
    ADD COLUMN pending_email VARCHAR(190) NULL AFTER email,
    ADD COLUMN pending_email_requested_at TIMESTAMP NULL AFTER pending_email;
