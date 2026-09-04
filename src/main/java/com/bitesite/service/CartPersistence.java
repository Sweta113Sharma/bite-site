package com.bitesite.service;

import com.bitesite.dao.SavedCartDao;
import com.bitesite.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Keeps a cart alive across sessions.
 *
 * <p>{@link Cart} is session-scoped and sessions last thirty minutes, so a student who
 * filled a cart and then sat through a lecture came back to an empty one — with no
 * explanation, because an expired session is indistinguishable from a first visit. Browse
 * before class, order after it is a normal thing to do at a canteen.
 *
 * <p>Restoring happens once per session rather than once per request: the flag lives on
 * the session-scoped bean, so this costs one read per session and nothing thereafter. It
 * only ever restores into an <em>empty</em> cart, so a saved cart can never overwrite what
 * someone is in the middle of building.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartPersistence {

    private final SavedCartDao savedCartDao;

    /** Restores a saved cart into an empty session cart, at most once per session. */
    public void hydrateOnce(User user, Cart cart) {
        if (cart.isHydrated()) {
            return;
        }
        cart.setHydrated(true);
        if (!cart.isEmpty()) {
            return;
        }
        Long outletId = savedCartDao.findOutletId(user.getId());
        if (outletId == null) {
            return;
        }
        Map<Long, Integer> items = savedCartDao.findItems(user.getId());
        if (items.isEmpty()) {
            return;
        }
        // ensureOutlet first: it clears on an outlet change, so setting it after the items
        // would wipe the very thing being restored.
        cart.ensureOutlet(outletId);
        items.forEach(cart::add);
    }

    /** Writes the current cart. Called after every change, so a session that ends
     * unexpectedly has already saved. */
    public void persist(User user, Cart cart) {
        try {
            if (cart.isEmpty() || cart.getOutletId() == null) {
                savedCartDao.clear(user.getId());
            } else {
                savedCartDao.save(user.getId(), cart.getOutletId(), cart.getQuantities());
            }
        } catch (RuntimeException e) {
            // The session cart is the one being used right now; persisting is a
            // convenience for later. Never fail somebody's add-to-cart over it.
            log.warn("Could not persist the cart for user {}", user.getId(), e);
        }
    }
}
