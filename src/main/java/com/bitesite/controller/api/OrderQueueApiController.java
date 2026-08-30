package com.bitesite.controller.api;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.PortalGuard;
import com.bitesite.dto.OrderQueueItem;
import com.bitesite.model.StaffScope;
import com.bitesite.model.User;
import com.bitesite.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderQueueApiController {

    private final OrderService orderService;

    @GetMapping("/queue")
    public List<OrderQueueItem> queue(@AuthenticationPrincipal AppUserPrincipal principal) {
        // Must carry the same scope as the page it feeds. If these two ever disagree the
        // queue silently stops refreshing for whichever role the API forgot.
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_OPS);
        User user = principal.getUser();
        return orderService.kitchenQueue(user.getTenantId(), user.getOutletId()).stream()
                .map(OrderQueueItem::from)
                .toList();
    }
}
