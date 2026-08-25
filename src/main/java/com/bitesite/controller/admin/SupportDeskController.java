package com.bitesite.controller.admin;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.dao.OrderDao;
import com.bitesite.dao.PaymentDao;
import com.bitesite.dao.UserDao;
import com.bitesite.dto.SupportOrderView;
import com.bitesite.model.Order;
import com.bitesite.model.Payment;
import com.bitesite.tenant.Tenant;
import com.bitesite.model.User;
import com.bitesite.service.OrderService;
import com.bitesite.tenant.TenantDao;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The support desk: look an order up from what a student can actually tell you, then
 * act on it.
 *
 * <p>This exists because the published refund policy promises both halves and the app
 * could do neither. It tells students to "raise it immediately through Support with your
 * payment reference — this is investigated and refunded directly", and for orders past
 * PREPARING, that staff "can review and refund it manually if appropriate". There was no
 * screen to search a payment on and no way for an admin to refund anything.
 *
 * <p>Every lookup here crosses tenant boundaries, which nothing else in the app does: a
 * super admin holds no tenantId, and a student on the phone does not know theirs. That is
 * safe only because {@code /admin/**} is already restricted to SUPER_ADMIN and
 * TECH_MANAGER in SecurityConfig. The refund action is narrowed further, below.
 */
@Controller
@RequestMapping("/admin/support")
@RequiredArgsConstructor
@Slf4j
public class SupportDeskController {

    private final OrderDao orderDao;
    private final PaymentDao paymentDao;
    private final UserDao userDao;
    private final TenantDao tenantDao;
    private final OrderService orderService;

    @GetMapping
    public String search(@RequestParam(required = false) String q, Model model) {
        model.addAttribute("pageTitle", "Support desk");
        model.addAttribute("q", q);

        if (q == null || q.isBlank()) {
            // Set explicitly rather than left absent: Thymeleaf resolves a missing
            // variable to null, and SpEL refuses to convert null to boolean, so a th:if
            // reading it would blow up on the empty landing state.
            model.addAttribute("searched", false);
            model.addAttribute("results", List.of());
            return "admin/support";
        }
        String query = q.trim();
        List<SupportOrderView> results = new ArrayList<>();

        // A Razorpay reference identifies exactly one payment, so try that first and
        // walk back to its order. Tokens are matched second and can return several.
        Optional<Payment> byReference = paymentDao.findByAnyGatewayReference(query);
        if (byReference.isPresent()) {
            Payment payment = byReference.get();
            orderDao.findByIdAndTenantId(payment.getOrderId(), payment.getTenantId())
                    .ifPresent(order -> results.add(view(order, payment)));
        } else {
            for (Order order : orderDao.searchByTokenAcrossTenants(query)) {
                results.add(view(order, paymentDao.findByOrderId(order.getId(), order.getTenantId()).orElse(null)));
            }
        }

        model.addAttribute("results", results);
        model.addAttribute("searched", true);
        return "admin/support";
    }

    /**
     * Refunds a captured payment.
     *
     * <p>Restricted to SUPER_ADMIN rather than inheriting the SecurityConfig rule for
     * {@code /admin/**}: that rule also admits TECH_MANAGER, which is an operations role
     * that has no business moving money. Everything else on this screen is read-only, so
     * this is the one action that needed narrowing.
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping("/orders/{orderId}/refund")
    public String refund(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long orderId,
            @RequestParam Long tenantId, @RequestParam(required = false) String reason,
            @RequestParam(required = false) String q) {
        orderService.refundOrder(orderId, tenantId, principal.getUser().getId(),
                reason == null || reason.isBlank() ? "no reason given" : reason);
        // Encoded: a token pasted with a space or an "&" would otherwise truncate the
        // query, or worse, graft an extra parameter onto the redirect.
        return "redirect:/admin/support?q="
                + URLEncoder.encode(q == null ? "" : q, StandardCharsets.UTF_8);
    }

    private SupportOrderView view(Order order, Payment payment) {
        String studentName = userDao.findById(order.getUserId()).map(User::getName).orElse("(deleted user)");
        String collegeName = tenantDao.findById(order.getTenantId()).map(Tenant::getName).orElse("(unknown)");
        return new SupportOrderView(order, payment, studentName, collegeName);
    }
}
