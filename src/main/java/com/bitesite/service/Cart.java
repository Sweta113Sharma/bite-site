package com.bitesite.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One cart per HTTP session. Deliberately holds only menuItemId -> quantity, never a
 * price — every price used at checkout is re-read from {@code MenuService} at checkout
 * time, so a stale or tampered client can never affect what's actually charged. Also
 * scoped to a single outlet at a time: switching outlets empties the cart rather than
 * mixing items from two different canteens into one order.
 *
 * <p><b>Why this is not a {@code @SessionScope} bean any more.</b> It was, and that put a
 * database write on the majority of page renders in the app. Spring re-{@code
 * setAttribute}s any session-scoped bean that a request merely <em>read</em> — {@code
 * ServletRequestAttributes.updateAccessedSessionAttributes()} does it so mutations reach a
 * distributed store — which marks the attribute dirty, and Spring Session JDBC then writes
 * the whole serialized object back to MySQL. Reading the cart to render a badge cost a
 * BLOB write. Worse, that write lands after the response has already committed, which made
 * Spring Session save twice and re-read the session in between (see the audit, 2.3).
 *
 * <p>So the state now lives in the session under one key and is written only when it
 * actually changes. Reads never write. The public API is unchanged, so callers did not
 * have to care.
 *
 * <p>Reads deliberately use {@code getSession(false)}: asking for the cart must not
 * conjure a session for a visitor who has not got one, which is what made anonymous page
 * views create session rows.
 *
 * <p>A deploy invalidates carts held in old sessions, because they were stored under
 * Spring's own scoped-bean key. That is covered: {@link CartPersistence#hydrateOnce} sees
 * an unhydrated cart and restores it from {@code saved_carts}.
 */
@Component
@RequiredArgsConstructor
public class Cart {

    /** Spring injects a thread-bound proxy here, so this singleton reads the current request. */
    private final HttpServletRequest request;

    static final String SESSION_KEY = "bitesite.cart";

    /** The part that is actually serialized into the session row. */
    static final class State implements Serializable {
        private static final long serialVersionUID = 1L;
        Long outletId;
        boolean hydrated;
        /** Only the text of the code. What it is worth is decided at checkout, every time. */
        String promoCode;
        final Map<Long, Integer> quantities = new LinkedHashMap<>();
    }

    /** The stored state, or a detached empty one when there is no session to read. */
    private State state() {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return new State();
        }
        State state = (State) session.getAttribute(SESSION_KEY);
        if (state == null) {
            state = new State();
            // Not stored yet: an empty cart is not worth a session write. It is written
            // by save() on the first real change.
        }
        return state;
    }

    /** Writes the state back, marking the attribute dirty so Spring Session persists it. */
    private void save(State state) {
        request.getSession(true).setAttribute(SESSION_KEY, state);
    }

    public Long getOutletId() {
        return state().outletId;
    }

    public Map<Long, Integer> getQuantities() {
        return Collections.unmodifiableMap(state().quantities);
    }

    public boolean isHydrated() {
        return state().hydrated;
    }

    /**
     * The code the student typed, or null.
     *
     * <p>Deliberately the text and nothing else. Holding the discount here would mean a
     * number that decides money living in a session the student can outlast: they could
     * apply a code to a ₹500 cart, empty it down to ₹40, and still carry the ₹100. The
     * code is re-priced against the real cart on every render and again at checkout.
     */
    public String getPromoCode() {
        return state().promoCode;
    }

    public void setPromoCode(String code) {
        State state = state();
        state.promoCode = code;
        save(state);
    }

    public void setHydrated(boolean hydrated) {
        State state = state();
        state.hydrated = hydrated;
        save(state);
    }

    public void ensureOutlet(Long outletId) {
        State state = state();
        if (!Objects.equals(state.outletId, outletId)) {
            state.quantities.clear();
            // A code may have been scoped to the canteen being left behind.
            state.promoCode = null;
            state.outletId = outletId;
            save(state);
        }
        // Same outlet: nothing changed, so nothing is written. This is the path every
        // menu page render takes, and it used to cost a write every time.
    }

    public void add(Long menuItemId, int qty) {
        if (qty <= 0) {
            return;
        }
        State state = state();
        state.quantities.merge(menuItemId, qty, Integer::sum);
        save(state);
    }

    public void setQuantity(Long menuItemId, int qty) {
        State state = state();
        if (qty <= 0) {
            state.quantities.remove(menuItemId);
        } else {
            state.quantities.put(menuItemId, qty);
        }
        save(state);
    }

    public void remove(Long menuItemId) {
        State state = state();
        state.quantities.remove(menuItemId);
        save(state);
    }

    public boolean isEmpty() {
        return state().quantities.isEmpty();
    }

    public void clear() {
        State state = state();
        state.quantities.clear();
        state.outletId = null;
        state.promoCode = null;
        save(state);
    }
}
