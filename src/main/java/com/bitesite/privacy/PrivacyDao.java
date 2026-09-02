package com.bitesite.privacy;

import java.util.List;

public interface PrivacyDao {

    /** Records consent. Re-granting an already-live consent is a no-op rather than a
     * duplicate row, so a student re-ticking a box does not litter their history. */
    void grantConsent(Long userId, ConsentPurpose purpose, String policyVersion);

    /** Marks a live consent withdrawn. The row stays: "they withdrew on the 3rd" is the
     * fact worth keeping, and deleting it would erase the evidence consent ever existed. */
    void withdrawConsent(Long userId, ConsentPurpose purpose);

    /** Every consent record for one user, newest first — their own audit trail. */
    List<Consent> findConsents(Long userId);

    void saveRequest(DataRequest request);

    /** The admin queue, newest first, optionally narrowed by status. */
    List<DataRequest> findRequests(DataRequest.Status status, int limit);

    void updateRequestStatus(Long id, DataRequest.Status status);

    /** One consent record. */
    record Consent(ConsentPurpose purpose, String policyVersion,
                   java.time.LocalDateTime grantedAt, java.time.LocalDateTime withdrawnAt) {
        public boolean live() {
            return withdrawnAt == null;
        }
    }
}
