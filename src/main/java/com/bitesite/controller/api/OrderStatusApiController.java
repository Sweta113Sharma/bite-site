package com.bitesite.controller.api;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.model.Order;
import com.bitesite.model.User;
import com.bitesite.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The current status of one order, for the page the student is sitting on.
 *
 * <p>The order detail page rendered whatever the status was at page load and never moved
 * again. That is the page a student watches while they wait — so the one screen in the
 * product dedicated to "is it ready yet" was the screen least able to answer it. The
 * sticky strip polled, but only ever described the most urgent order, not the one being
 * looked at.
 *
 * <p>Returns the status string and nothing else. The client compares it with what is
 * rendered and reloads on a change rather than repainting, so the status copy, the
 * medallion, the pickup code and everything else stay resolved server-side in one place
 * instead of being reimplemented in JavaScript.
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderStatusApiController {

    private final OrderService orderService;

    @GetMapping("/{orderId}/status")
    public Map<String, String> status(@AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long orderId) {
        User user = principal.getUser();
        // Scoped to the caller's own order — getForUser 404s on anyone else's, so the id
        // in the path is not a way to watch a stranger's lunch.
        Order order = orderService.getForUser(orderId, user.getId(), user.getTenantId());
        return Map.of("status", order.getStatus().name());
    }
}
