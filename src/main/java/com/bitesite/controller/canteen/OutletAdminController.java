package com.bitesite.controller.canteen;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.PortalGuard;
import com.bitesite.dao.OrderDao;
import com.bitesite.exception.BusinessException;
import com.bitesite.exception.DuplicateEmailException;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.dto.Paged;
import com.bitesite.model.Order;
import com.bitesite.model.OrderStatus;
import com.bitesite.model.Role;
import com.bitesite.model.StaffScope;
import com.bitesite.model.User;
import com.bitesite.service.OrderService;
import com.bitesite.service.OutletService;
import com.bitesite.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalTime;
import java.util.List;

/**
 * Everything an outlet needs beyond the live queue and the menu editor: order history,
 * a sales report, its own settings, and its own staff.
 *
 * <p>Until now a canteen could not staff itself — the only path that created outlet
 * accounts was the platform admin's tenant screen, which also hardcoded the role. A
 * manager can now invite their own operators, scoped to their own outlet: the outlet id
 * comes from the signed-in manager, never from the request, so this cannot be used to
 * place an account at someone else's canteen.
 */
@Controller
@RequestMapping("/canteen")
@RequiredArgsConstructor
public class OutletAdminController {

    /** Rows per page of order history. Was a flat 200-row cap with no way past it. */
    private static final int HISTORY_PAGE_SIZE = 50;
    private static final int REPORT_DAYS = 30;

    private final OrderDao orderDao;
    private final OrderService orderService;
    private final OutletService outletService;
    private final UserService userService;

    // ---------- Order history ----------

    /** Both roles: an operator fielding "where is my order" needs to look it up. */
    @GetMapping("/orders")
    public String history(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_OPS);
        User user = principal.getUser();
        int safePage = Math.max(0, page);
        Paged<Order> paged = Paged.of(
                orderDao.findByOutlet(user.getTenantId(), user.getOutletId(), status,
                        HISTORY_PAGE_SIZE + 1, Paged.offsetFor(safePage, HISTORY_PAGE_SIZE)),
                safePage, HISTORY_PAGE_SIZE);
        model.addAttribute("paged", paged);
        model.addAttribute("orders", paged.items());
        model.addAttribute("statuses", OrderStatus.values());
        model.addAttribute("selectedStatus", status);
        model.addAttribute("pageTitle", "Order history");
        return "canteen/orders";
    }

    @GetMapping("/orders/{orderId}")
    public String orderDetail(@AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long orderId, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_OPS);
        User user = principal.getUser();
        Order order = orderService.getForTenant(orderId, user.getTenantId());
        // Tenant-scoped is not enough here: staff belong to one outlet, and an order from
        // the college's other canteen is none of their business.
        if (!order.getOutletId().equals(user.getOutletId())) {
            throw new ResourceNotFoundException("Order not found");
        }
        model.addAttribute("order", order);
        model.addAttribute("payment",
                orderService.findPaymentForOrder(orderId, user.getTenantId()).orElse(null));
        model.addAttribute("pageTitle", "Order " + order.getTokenNo());
        return "canteen/order-detail";
    }

    // ---------- Sales report ----------

    @GetMapping("/reports")
    public String reports(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_MANAGE);
        User user = principal.getUser();
        List<OrderDao.DailySales> rows =
                orderDao.dailySales(user.getTenantId(), user.getOutletId(), REPORT_DAYS);
        model.addAttribute("rows", rows);
        model.addAttribute("days", REPORT_DAYS);
        model.addAttribute("totalOrders", rows.stream().mapToInt(OrderDao.DailySales::orderCount).sum());
        model.addAttribute("totalRevenue", rows.stream()
                .map(OrderDao.DailySales::revenue)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add));
        model.addAttribute("pageTitle", "Sales");
        return "canteen/reports";
    }

    // ---------- Outlet settings ----------

    @GetMapping("/settings")
    public String settings(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_MANAGE);
        User user = principal.getUser();
        model.addAttribute("outlet", outletService.get(user.getOutletId(), user.getTenantId()));
        model.addAttribute("pageTitle", "Outlet settings");
        return "canteen/settings";
    }

    @PostMapping("/settings")
    public String saveSettings(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(required = false) @DateTimeFormat(pattern = "HH:mm") LocalTime opensAt,
            @RequestParam(required = false) @DateTimeFormat(pattern = "HH:mm") LocalTime closesAt,
            @RequestParam(required = false) String contactPhone,
            @RequestParam(required = false) String notice,
            RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_MANAGE);
        User user = principal.getUser();
        outletService.updateSettings(user.getOutletId(), user.getTenantId(),
                opensAt, closesAt, contactPhone, notice, user.getId());
        redirectAttributes.addFlashAttribute("settingsNotice", "Settings saved.");
        return "redirect:/canteen/settings";
    }

    // ---------- Staff ----------

    @GetMapping("/staff")
    public String staff(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_MANAGE);
        User user = principal.getUser();
        model.addAttribute("staff", userService.findByOutlet(user.getOutletId(), user.getTenantId()));
        model.addAttribute("pageTitle", "Staff");
        return "canteen/staff";
    }

    @PostMapping("/staff")
    public String addStaff(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam String name, @RequestParam String email, @RequestParam String password,
            @RequestParam Role role, RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_MANAGE);
        User user = principal.getUser();
        // Same check the admin path makes: a role parameter must not become a way to mint
        // a platform account, and a manager must not be able to exceed their own portal.
        if (!role.isOutletPortalRole()) {
            redirectAttributes.addFlashAttribute("staffError", "Staff must be a manager or an operator.");
            return "redirect:/canteen/staff";
        }
        try {
            // Tenant and outlet come from the signed-in manager, never the request.
            userService.createUser(user.getTenantId(), user.getOutletId(), name, email, password, role);
            redirectAttributes.addFlashAttribute("staffNotice", name + " can now sign in.");
        } catch (DuplicateEmailException e) {
            redirectAttributes.addFlashAttribute("staffError", e.getMessage());
        }
        return "redirect:/canteen/staff";
    }

    /**
     * Emails a reset code to a staff member. Before this, a forgotten password at an
     * outlet had no remedy short of a manager creating a second account — and the account
     * they abandoned kept its access.
     */
    @PostMapping("/staff/{userId}/password-reset")
    public String sendStaffPasswordReset(@AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long userId, RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_MANAGE);
        User user = principal.getUser();
        try {
            userService.sendStaffPasswordReset(userId, user.getOutletId(), user.getTenantId(), user.getId());
            redirectAttributes.addFlashAttribute("staffNotice",
                    "A reset code is on its way to their email. It expires in 10 minutes.");
        } catch (ResourceNotFoundException e) {
            // ResourceNotFoundException extends BusinessException, so without this branch
            // the one below swallows it and a bad id renders as a friendly notice on this
            // page instead of the 404 every other lookup here produces.
            throw e;
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("staffError", e.getMessage());
        }
        return "redirect:/canteen/staff";
    }

    @PostMapping("/staff/{userId}/deactivate")
    public String deactivateStaff(@AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long userId, RedirectAttributes redirectAttributes) {
        PortalGuard.requireScope(principal.getUser(), StaffScope.OUTLET_MANAGE);
        User user = principal.getUser();
        if (userId.equals(user.getId())) {
            // Otherwise a sole manager can lock their own outlet out of its own console.
            redirectAttributes.addFlashAttribute("staffError", "You can't deactivate your own account.");
            return "redirect:/canteen/staff";
        }
        userService.deactivateOutletstaff(userId, user.getOutletId(), user.getTenantId(), user.getId());
        redirectAttributes.addFlashAttribute("staffNotice", "Account deactivated.");
        return "redirect:/canteen/staff";
    }
}
