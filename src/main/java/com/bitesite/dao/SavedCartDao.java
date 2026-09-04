package com.bitesite.dao;

import java.util.Map;

/** A student's cart, kept across sessions. See V23. */
public interface SavedCartDao {

    /** Outlet the saved cart belongs to, or null when nothing is saved. */
    Long findOutletId(Long userId);

    /** menuItemId -> quantity. Empty when nothing is saved. */
    Map<Long, Integer> findItems(Long userId);

    /** Replaces whatever was saved. Called on every cart change, so it is a full rewrite
     * rather than a diff — a cart is a handful of rows and correctness beats cleverness. */
    void save(Long userId, Long outletId, Map<Long, Integer> quantities);

    void clear(Long userId);
}
