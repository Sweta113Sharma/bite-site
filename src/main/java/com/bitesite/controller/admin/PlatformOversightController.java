package com.bitesite.controller.admin;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.PortalGuard;
import com.bitesite.dao.OrderDao;
import com.bitesite.dao.OutletDao;
import com.bitesite.dao.PaymentDao;
import com.bitesite.dao.UserDao;
import com.bitesite.model.Order;
import com.bitesite.model.OrderStatus;
import com.bitesite.dto.Paged;
import com.bitesite.model.Outlet;
import com.bitesite.model.Payment;
import com.bitesite.model.PaymentStatus;
import com.bitesite.model.StaffScope;
import com.bitesite.model.User;
import com.bitesite.tenant.Tenant;
import com.bitesite.tenant.TenantDao;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-only oversight across every college: outlets, orders, and payments.
 *
 * <p>Every query here crosses tenant boundaries, which almost nothing else in this app
 * does. That is safe for the same reason the support desk is: {@code /admin/**} is already
 * restricted to SUPER_ADMIN and TECH_MANAGER in SecurityConfig, and each handler narrows
 * further below. A platform account holds no tenantId, so there is nothing to scope by —
 * which also means {@code TenantContext} stays null for these requests and there is no
 * ambient tenant to inherit. Every row therefore prints the college it belongs to.
 *
 * <p>Deliberately read-only. Anything that moves money or changes state lives on the
 * support desk, where it is narrowed to SUPER_ADMIN.
 */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class PlatformOversightController {

    /** Rows per page. These lists are now paged rather than truncated: the old behaviour
     * showed the most recent hundred and offered no way at all to reach the hundred-and-first. */
    private static final int PAGE_SIZE = 50;

    private final OutletDao outletDao;
    private final OrderDao orderDao;
    private final PaymentDao paymentDao;
    private final TenantDao tenantDao;
    private final UserDao userDao;

    @GetMapping("/outlets")
    public String outlets(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OPS_SCOPE);
        List<Outlet> outlets = outletDao.findAllAcrossTenants();
        model.addAttribute("outlets", outlets);
        model.addAttribute("collegeNames", collegeNames());
        model.addAttribute("pageTitle", "All canteens");
        return "admin/outlets";
    }

    @GetMapping("/orders")
    public String orders(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OPS_SCOPE);
        int safePage = Math.max(0, page);
        // One row more than the page needs, so Paged can tell whether there is a next one.
        Paged<Order> paged = Paged.of(
                orderDao.findRecentAcrossTenants(tenantId, status, q, PAGE_SIZE + 1,
                        Paged.offsetFor(safePage, PAGE_SIZE)),
                safePage, PAGE_SIZE);
        List<Order> orders = paged.items();
        model.addAttribute("paged", paged);
        model.addAttribute("q", q);
        model.addAttribute("orders", orders);
        model.addAttribute("collegeNames", collegeNames());
        // Resolved per row rather than joined, matching how the support desk builds its
        // view — one small lookup map beats threading a join through the row mapper.
        model.addAttribute("studentNames", namesFor(orders));
        model.addAttribute("tenants", tenantDao.findAll());
        model.addAttribute("statuses", OrderStatus.values());
        model.addAttribute("selectedTenantId", tenantId);
        model.addAttribute("selectedStatus", status);
        model.addAttribute("pageTitle", "All orders");
        return "admin/orders";
    }

    @GetMapping("/payments")
    public String payments(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(defaultValue = "0") int page, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OPS_SCOPE);
        int safePage = Math.max(0, page);
        Paged<Payment> paged = Paged.of(
                paymentDao.findRecentAcrossTenants(status, PAGE_SIZE + 1,
                        Paged.offsetFor(safePage, PAGE_SIZE)),
                safePage, PAGE_SIZE);
        List<Payment> payments = paged.items();
        model.addAttribute("paged", paged);
        model.addAttribute("payments", payments);
        model.addAttribute("collegeNames", collegeNames());
        model.addAttribute("statuses", PaymentStatus.values());
        model.addAttribute("selectedStatus", status);
        // The number worth seeing first: money taken and not yet settled into an order.
        model.addAttribute("capturedCount", payments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.CAPTURED).count());
        model.addAttribute("pageTitle", "Payments");
        return "admin/payments";
    }

    /** tenantId -> college name, so a cross-tenant table can label every row. */
    private Map<Long, String> collegeNames() {
        return tenantDao.findAll().stream()
                .collect(Collectors.toMap(Tenant::getId, Tenant::getName, (a, b) -> a));
    }

    private Map<Long, String> namesFor(List<Order> orders) {
        Map<Long, String> names = new HashMap<>();
        for (Order order : orders) {
            names.computeIfAbsent(order.getUserId(), id ->
                    userDao.findById(id).map(User::getName).orElse("(deleted user)"));
        }
        return names;
    }
}
