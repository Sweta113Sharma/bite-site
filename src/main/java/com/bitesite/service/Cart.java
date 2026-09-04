package com.bitesite.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One cart per HTTP session (Spring session-scoped bean). Deliberately holds only
 * menuItemId -> quantity, never a price — every price used at checkout is re-read from
 * {@code MenuService} at checkout time, so a stale or tampered client can never affect
 * what's actually charged. Also scoped to a single outlet at a time: switching outlets
 * empties the cart rather than mixing items from two different canteens into one order.
 *
 * <p>Serializable because Spring Session JDBC persists session-scoped bean instances as
 * a serialized blob per session row.
 */
@Component
@SessionScope
@Getter
public class Cart implements Serializable {

    private Long outletId;

    /** Whether this session has already tried to restore a saved cart. One attempt per
     * session, not per page — see CartPersistence. */
    @Setter
    private boolean hydrated;

    private final Map<Long, Integer> quantities = new LinkedHashMap<>();

    public void ensureOutlet(Long outletId) {
        if (!Objects.equals(this.outletId, outletId)) {
            quantities.clear();
            this.outletId = outletId;
        }
    }

    public void add(Long menuItemId, int qty) {
        if (qty <= 0) {
            return;
        }
        quantities.merge(menuItemId, qty, Integer::sum);
    }

    public void setQuantity(Long menuItemId, int qty) {
        if (qty <= 0) {
            quantities.remove(menuItemId);
        } else {
            quantities.put(menuItemId, qty);
        }
    }

    public void remove(Long menuItemId) {
        quantities.remove(menuItemId);
    }

    public boolean isEmpty() {
        return quantities.isEmpty();
    }

    public void clear() {
        quantities.clear();
        outletId = null;
    }
}
