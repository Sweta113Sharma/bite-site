-- A cart that outlives the session it was built in.
--
-- Cart is a @SessionScope bean, and sessions expire after thirty minutes. So a student who
-- filled a cart, sat through a lecture and came back found it empty — with no explanation,
-- because an expired session looks exactly like a fresh visit. At a canteen that is a
-- routine thing to do: browse before class, order after it.
--
-- One row per user rather than per session: the point is that it survives the session.
-- The outlet is stored alongside, because a cart only means anything against the canteen
-- it was built from (see Cart.ensureOutlet) and restoring items without it would let a
-- student order one canteen's menu from another.
--
-- No prices here. This is a list of intentions, not an order; everything is repriced from
-- the menu at checkout, which is where the money is decided.
CREATE TABLE saved_carts (
    user_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    outlet_id BIGINT UNSIGNED NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_saved_carts_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_saved_carts_outlet FOREIGN KEY (outlet_id) REFERENCES outlets(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE saved_cart_items (
    user_id BIGINT UNSIGNED NOT NULL,
    menu_item_id BIGINT UNSIGNED NOT NULL,
    quantity INT NOT NULL,
    PRIMARY KEY (user_id, menu_item_id),
    CONSTRAINT fk_saved_cart_items_cart FOREIGN KEY (user_id) REFERENCES saved_carts(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_saved_cart_items_item FOREIGN KEY (menu_item_id) REFERENCES menu_items(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
