package com.bitesite.controller.api;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.dto.ActiveOrderStripView;
import com.bitesite.model.Role;
import com.bitesite.model.User;
import com.bitesite.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Feeds the sticky order strip.
 *
 * <p>The strip was rendered once, server-side, from the model attribute a page was built
 * with — so it only ever told the truth at the moment the page loaded. A student who left
 * the menu open watched "Being prepared" long after collecting the food, and the strip for
 * a completed order stayed on screen until something happened to trigger a navigation.
 * This is the same shape as the kitchen queue's poll: a small JSON view the client
 * re-reads on a timer.
 *
 * <p>Scoped to the caller's own orders — the user id comes from the authenticated
 * principal, never from the request, so there is no id here to tamper with.
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class ActiveOrderApiController {

    private final OrderService orderService;

    /** Returns the strip to draw, or {@code null} when there is nothing live — which is
     * the signal for the client to remove a strip that is no longer true. */
    @GetMapping("/active")
    public ActiveOrderStripView active(@AuthenticationPrincipal AppUserPrincipal principal) {
        User user = principal.getUser();
        // Mirrors GlobalModelAttributes#activeOrders: staff and admins have no strip, so
        // they never run the query. A tenant-less account has no orders by definition.
        if (user.getActiveRole() != Role.USER || user.getTenantId() == null) {
            return null;
        }
        return ActiveOrderStripView.from(orderService.liveForUser(user.getId(), user.getTenantId()));
    }
}
