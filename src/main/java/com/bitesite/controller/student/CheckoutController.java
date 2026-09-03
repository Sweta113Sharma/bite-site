package com.bitesite.controller.student;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.config.RateLimiter;
import com.bitesite.config.RazorpayProperties;
import com.bitesite.dto.CheckoutResult;
import com.bitesite.exception.InvalidOrderStateException;
import com.bitesite.model.Order;
import com.bitesite.model.OrderStatus;
import com.bitesite.model.Payment;
import com.bitesite.model.User;
import com.bitesite.service.Cart;
import com.bitesite.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

@Controller
@RequestMapping("/student/checkout")
@RequiredArgsConstructor
public class CheckoutController {

    private static final int MAX_CHECKOUTS_PER_WINDOW = 5;
    private static final Duration CHECKOUT_WINDOW = Duration.ofMinutes(1);

    private final Cart cart;
    private final OrderService orderService;
    private final RazorpayProperties razorpayProperties;
    private final RateLimiter rateLimiter;

    @PostMapping
    public String checkout(@AuthenticationPrincipal AppUserPrincipal principal, RedirectAttributes redirectAttributes) {
        User user = principal.getUser();
        if (cart.isEmpty() || cart.getOutletId() == null) {
            return "redirect:/student/cart";
        }
        if (!rateLimiter.tryConsume("checkout:" + user.getId(), MAX_CHECKOUTS_PER_WINDOW, CHECKOUT_WINDOW)) {
            redirectAttributes.addFlashAttribute("cartError", "Too many checkout attempts — please wait a moment and try again.");
            return "redirect:/student/cart";
        }
        try {
            CheckoutResult result = orderService.checkout(
                    user.getTenantId(), cart.getOutletId(), user.getId(), cart.getQuantities());
            cart.clear();
            return "redirect:/student/checkout/" + result.order().getId();
        } catch (InvalidOrderStateException e) {
            redirectAttributes.addFlashAttribute("cartError", e.getMessage());
            return "redirect:/student/cart";
        }
    }

    @GetMapping("/{orderId}")
    public String pay(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long orderId, Model model) {
        User user = principal.getUser();
        Order order = orderService.getForUser(orderId, user.getId(), user.getTenantId());
        if (order.getStatus() != OrderStatus.AWAITING_PAYMENT) {
            return "redirect:/student/orders/" + orderId;
        }
        Payment payment = orderService.getPaymentForOrder(orderId, user.getTenantId());
        long amountPaise = payment.getAmount()
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
        model.addAttribute("order", order);
        model.addAttribute("payment", payment);
        model.addAttribute("amountPaise", amountPaise);
        model.addAttribute("razorpayKeyId", razorpayProperties.keyId());
        // Handed to Razorpay's prefill so the student is not asked at the till for details
        // this account already holds. Phone is optional at registration, so it may be blank
        // — Razorpay asks for it only when it is, which is the point.
        model.addAttribute("payerName", user.getName());
        model.addAttribute("payerEmail", user.getEmail());
        model.addAttribute("payerPhone", user.getPhone() == null ? "" : user.getPhone());
        model.addAttribute("pageTitle", "Pay for your order");
        return "student/checkout";
    }

    @PostMapping("/{orderId}/confirm")
    public String confirm(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long orderId,
            @RequestParam("razorpay_order_id") String gatewayOrderId,
            @RequestParam("razorpay_payment_id") String gatewayPaymentId,
            @RequestParam("razorpay_signature") String signature,
            RedirectAttributes redirectAttributes) {
        User user = principal.getUser();
        orderService.getForUser(orderId, user.getId(), user.getTenantId());
        boolean confirmed = orderService.confirmPayment(gatewayOrderId, gatewayPaymentId, signature);
        if (!confirmed) {
            redirectAttributes.addFlashAttribute("paymentError", "We couldn't verify that payment — please retry.");
            return "redirect:/student/checkout/" + orderId;
        }
        // Money has just left a student's account and the old redirect said nothing at
        // all — they landed on the order page and had to work out from the badge whether
        // it had worked. This is the most anxious moment in the product; it should be the
        // most clearly answered.
        redirectAttributes.addFlashAttribute("paymentSucceeded", true);
        return "redirect:/student/orders/" + orderId;
    }
}
