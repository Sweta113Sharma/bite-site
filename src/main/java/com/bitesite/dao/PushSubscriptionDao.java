package com.bitesite.dao;

import com.bitesite.model.PushSubscription;

import java.util.List;

public interface PushSubscriptionDao {
    /** Upserts on endpoint — resubscribing (or a different account signing in on the same
     * browser) updates which user the existing endpoint belongs to rather than erroring. */
    void save(PushSubscription subscription);

    List<PushSubscription> findByUserId(Long userId);

    void deleteByEndpoint(String endpoint);

    /** Deletes an endpoint only if it belongs to this user. Returns the rows removed, so
     * callers can tell a genuine unsubscribe from an attempt on someone else's device. */
    int deleteByEndpointForUser(String endpoint, Long userId);

    /** Every subscription a user holds — used when erasing an account, so a "deleted"
     * account's devices stop receiving notifications. */
    void deleteByUserId(Long userId);
}
