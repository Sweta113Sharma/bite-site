package com.bitesite.dao;

import com.bitesite.model.PushSubscription;

import java.util.List;

public interface PushSubscriptionDao {
    /** Upserts on endpoint — resubscribing (or a different account signing in on the same
     * browser) updates which user the existing endpoint belongs to rather than erroring. */
    void save(PushSubscription subscription);

    List<PushSubscription> findByUserId(Long userId);

    void deleteByEndpoint(String endpoint);
}
