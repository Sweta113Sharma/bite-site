package com.bitesite.service;

import com.bitesite.dao.OrderDao;
import com.bitesite.dao.PaymentDao;
import com.bitesite.dto.CheckoutResult;
import com.bitesite.dto.GatewayOrder;
import com.bitesite.exception.InvalidOrderStateException;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.MenuItem;
import com.bitesite.model.Order;
import com.bitesite.model.OrderItem;
import com.bitesite.model.OrderStatus;
import com.bitesite.model.Payment;
import com.bitesite.model.PaymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OrderService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_GENERATION_ATTEMPTS = 10;

    private final OrderDao orderDao;
    private final PaymentDao paymentDao;
    private final MenuService menuService;
    private final PaymentGateway paymentGateway;
    private final AuditService auditService;
    private final PushNotificationService pushNotificationService;

    /**
     * Builds the order from the cart (re-pricing every line from the database, never
     * trusting anything cached client-side), persists it as AWAITING_PAYMENT, then starts
     * a payment-gateway order. The order row commits on its own short transaction before
     * the external gateway call runs, so a slow/failed network call never holds a DB
     * transaction open.
     */
    public CheckoutResult checkout(Long tenantId, Long outletId, Long userId, Map<Long, Integer> cartQuantities) {
        if (cartQuantities.isEmpty()) {
            throw new InvalidOrderStateException("Your cart is empty.");
        }

        List<OrderItem> orderItems = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<Long, Integer> entry : cartQuantities.entrySet()) {
            MenuItem item = menuService.get(entry.getKey(), tenantId);
            if (!item.isAvailable() || !item.getOutletId().equals(outletId)) {
                throw new InvalidOrderStateException(item.getName() + " is no longer available.");
            }
            BigDecimal unitPrice = item.effectivePrice();
            BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(entry.getValue()));
            total = total.add(subtotal);
            orderItems.add(OrderItem.builder()
                    .menuItemId(item.getId())
                    .itemNameSnapshot(item.getName())
                    .quantity(entry.getValue())
                    .unitPrice(unitPrice)
                    .subtotal(subtotal)
                    .build());
        }

        Order order = Order.builder()
                .tenantId(tenantId)
                .outletId(outletId)
                .userId(userId)
                .tokenNo(generateUniqueToken(tenantId))
                .totalAmount(total)
                .status(OrderStatus.AWAITING_PAYMENT)
                .items(orderItems)
                .build();
        Order saved = orderDao.createOrder(order);

        GatewayOrder gatewayOrder;
        try {
            gatewayOrder = paymentGateway.createOrder(total, saved.getTokenNo());
        } catch (RuntimeException e) {
            orderDao.updateStatus(saved.getId(), tenantId, OrderStatus.PAYMENT_FAILED);
            throw e;
        }

        Payment payment = Payment.builder()
                .tenantId(tenantId)
                .orderId(saved.getId())
                .razorpayOrderId(gatewayOrder.gatewayOrderId())
                .amount(total)
                .status(PaymentStatus.CREATED)
                .build();
        paymentDao.save(payment);

        return new CheckoutResult(saved, gatewayOrder);
    }

    private String generateUniqueToken(Long tenantId) {
        for (int attempt = 0; attempt < TOKEN_GENERATION_ATTEMPTS; attempt++) {
            String candidate = "BITE-" + (1000 + RANDOM.nextInt(9000));
            if (!orderDao.existsTokenForTenant(tenantId, candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique order token");
    }

    public Order getForUser(Long orderId, Long userId, Long tenantId) {
        Order order = getForTenant(orderId, tenantId);
        if (!order.getUserId().equals(userId)) {
            throw new ResourceNotFoundException("Order not found");
        }
        return order;
    }

    public Order getForTenant(Long orderId, Long tenantId) {
        return orderDao.findByIdAndTenantId(orderId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    }

    public List<Order> historyForUser(Long userId, Long tenantId) {
        return orderDao.findByUserId(userId, tenantId);
    }

    public List<Order> kitchenQueue(Long tenantId, Long outletId) {
        return orderDao.findKitchenQueue(tenantId, outletId);
    }

    public Payment getPaymentForOrder(Long orderId, Long tenantId) {
        return paymentDao.findByOrderId(orderId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
    }

    /**
     * Called by both the client-side checkout success callback and the async webhook —
     * idempotent so whichever arrives first wins and the other is a no-op.
     *
     * <p>{@code signature} is the per-payment signature Razorpay Checkout.js hands the
     * browser on success (order_id + "|" + payment_id, HMAC-signed) — pass it when called
     * from that path and it is verified here before anything is marked paid. The webhook
     * path has no equivalent per-payment signature (Razorpay signs the whole webhook body
     * instead, over a different header the caller must verify itself before calling this
     * method); pass {@code null} from there rather than fabricate a value to check.
     */
    @Transactional
    public boolean confirmPayment(String gatewayOrderId, String gatewayPaymentId, String signature) {
        Payment payment = paymentDao.findByRazorpayOrderId(gatewayOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            return true;
        }

        if (signature != null && !paymentGateway.verifyPaymentSignature(gatewayOrderId, gatewayPaymentId, signature)) {
            paymentDao.updateStatus(payment.getId(), PaymentStatus.FAILED);
            return false;
        }

        paymentDao.markVerified(payment.getId(), gatewayPaymentId, signature, PaymentStatus.CAPTURED);
        Order order = getForTenant(payment.getOrderId(), payment.getTenantId());
        if (order.getStatus().canTransitionTo(OrderStatus.PAID)) {
            orderDao.updateStatus(order.getId(), order.getTenantId(), OrderStatus.PAID);
        }
        return true;
    }

    public void advanceStatus(Long orderId, Long tenantId, OrderStatus newStatus, Long actorUserId) {
        Order order = getForTenant(orderId, tenantId);
        if (!order.getStatus().canTransitionTo(newStatus)) {
            throw new InvalidOrderStateException(
                    "Cannot move order from " + order.getStatus() + " to " + newStatus);
        }
        orderDao.updateStatus(orderId, tenantId, newStatus);
        auditService.record(actorUserId, tenantId, "Order", orderId, "STATUS_" + newStatus,
                order.getStatus(), newStatus);
        if (newStatus == OrderStatus.READY_FOR_PICKUP) {
            pushNotificationService.notifyUser(order.getUserId(), "Order ready for pickup",
                    "Your order " + order.getTokenNo() + " is ready — come grab it!");
        }
    }

    /**
     * Cancels an order, refunding it first if a payment was actually captured. The refund
     * call must succeed before anything in our own database changes — never mark an order
     * cancelled while leaving the customer's money uncollected from a refund that silently
     * failed. Orders that never reached PAID (AWAITING_PAYMENT, PAYMENT_FAILED) have nothing
     * captured, so those are cancelled directly with no refund call.
     */
    @Transactional
    public void cancelOrder(Long orderId, Long tenantId, Long actorUserId) {
        Order order = getForTenant(orderId, tenantId);
        if (!order.getStatus().canTransitionTo(OrderStatus.CANCELLED)) {
            throw new InvalidOrderStateException(
                    "Cannot cancel an order in " + order.getStatus() + " status.");
        }

        if (order.getStatus() == OrderStatus.PAID) {
            Payment payment = getPaymentForOrder(orderId, tenantId);
            if (payment.getStatus() == PaymentStatus.CAPTURED) {
                paymentGateway.refund(payment.getRazorpayPaymentId(), payment.getAmount());
                paymentDao.updateStatus(payment.getId(), PaymentStatus.REFUNDED);
            }
        }

        OrderStatus previous = order.getStatus();
        orderDao.updateStatus(orderId, tenantId, OrderStatus.CANCELLED);
        auditService.record(actorUserId, tenantId, "Order", orderId, "STATUS_CANCELLED", previous, OrderStatus.CANCELLED);
        pushNotificationService.notifyUser(order.getUserId(), "Order cancelled",
                "Your order " + order.getTokenNo() + " was cancelled" + (previous == OrderStatus.PAID ? " and refunded." : "."));
    }

    /** Sweeps unpaid orders past the payment timeout so they stop counting as pending
     * demand; call periodically (see {@link com.bitesite.config.OrderExpiryScheduler}). */
    public int expireStalePayments(int timeoutMinutes) {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(timeoutMinutes);
        List<Order> stale = orderDao.findExpiredAwaitingPayment(cutoff);
        for (Order order : stale) {
            orderDao.updateStatus(order.getId(), order.getTenantId(), OrderStatus.EXPIRED);
        }
        return stale.size();
    }
}
