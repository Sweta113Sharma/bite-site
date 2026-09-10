package com.bitesite.security;

import com.bitesite.dao.OrderDao;
import com.bitesite.dao.OutletDao;
import com.bitesite.dao.UserDao;
import com.bitesite.model.Order;
import com.bitesite.model.OrderItem;
import com.bitesite.model.OrderStatus;
import com.bitesite.model.Outlet;
import com.bitesite.model.Role;
import com.bitesite.model.User;
import com.bitesite.tenant.Tenant;
import com.bitesite.tenant.TenantDao;
import com.bitesite.tenant.TenantStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A student's order history, against real MySQL.
 *
 * <p>Two things are proved here, and the second is the one that could go quietly wrong.
 *
 * <p>First, the page is bounded. This screen used to load every order a student had ever
 * placed and filter it in Java, on a product people use daily, so the cost of opening it
 * grew forever.
 *
 * <p>Second, batching the item fetch attaches the right lines to the right orders. The
 * loop it replaced was N+1 but trivially correct: each order asked for its own items.
 * One query with an IN list and a grouping step can put order 7's samosa on order 9 and
 * nothing about the page would look broken — it would just be wrong, on the screen where
 * a student checks what they were charged for.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderHistoryPagingTest {

    @Autowired private TenantDao tenantDao;
    @Autowired private OutletDao outletDao;
    @Autowired private UserDao userDao;
    @Autowired private OrderDao orderDao;
    @Autowired private PasswordEncoder passwordEncoder;

    private Long tenantId;
    private Long userId;

    /** Each order gets a distinct item name so a mis-grouped batch is visible, not plausible. */
    private static String itemNameFor(int i) {
        return "item-for-order-" + i;
    }

    @BeforeAll
    void seedTwentyFiveFinishedOrdersAndThreeLive() {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        Tenant tenant = tenantDao.save(Tenant.builder().name("History College " + runId)
                .status(TenantStatus.ACTIVE).build());
        tenantId = tenant.getId();
        Outlet outlet = outletDao.save(Outlet.builder().tenantId(tenantId)
                .name("History Canteen").active(true).build());
        User student = userDao.save(User.builder().tenantId(tenantId).name("History Student")
                .email("history-" + runId + "@test.local").passwordHash(passwordEncoder.encode("x"))
                .role(Role.USER).activeRole(Role.USER).active(true).build());
        userId = student.getId();

        for (int i = 0; i < 25; i++) {
            orderDao.createOrder(Order.builder()
                    .tenantId(tenantId).outletId(outlet.getId()).userId(userId)
                    .tokenNo("H" + runId + "-" + i).totalAmount(new BigDecimal("40.00"))
                    .status(OrderStatus.COMPLETED)
                    .items(List.of(OrderItem.builder().menuItemId(1L)
                            .itemNameSnapshot(itemNameFor(i)).quantity(i + 1)
                            .unitPrice(new BigDecimal("40.00")).subtotal(new BigDecimal("40.00")).build()))
                    .build());
        }
        // Live orders must never appear in history; they are shown separately.
        for (int i = 0; i < 3; i++) {
            orderDao.createOrder(Order.builder()
                    .tenantId(tenantId).outletId(outlet.getId()).userId(userId)
                    .tokenNo("L" + runId + "-" + i).totalAmount(new BigDecimal("40.00"))
                    .status(OrderStatus.PAID).items(List.of()).build());
        }
    }

    @Test
    void aPageIsBoundedAndTheRestIsReachable() {
        List<Order> firstTen = orderDao.findTerminalByUserId(userId, tenantId, 10, 0);
        assertThat(firstTen).hasSize(10);

        List<Order> pageTwo = orderDao.findTerminalByUserId(userId, tenantId, 10, 10);
        assertThat(pageTwo).hasSize(10);
        assertThat(pageTwo).extracting(Order::getId)
                .as("a second page must not repeat the first")
                .doesNotContainAnyElementsOf(firstTen.stream().map(Order::getId).toList());

        List<Order> lastPage = orderDao.findTerminalByUserId(userId, tenantId, 10, 20);
        assertThat(lastPage)
                .as("25 finished orders, so the third page holds the remaining 5")
                .hasSize(5);
    }

    /** Live orders belong to the strip at the top of the page, never to the history list. */
    @Test
    void onlyFinishedOrdersAreListedAsHistory() {
        List<Order> all = orderDao.findTerminalByUserId(userId, tenantId, 100, 0);

        assertThat(all).hasSize(25);
        assertThat(all).extracting(Order::getStatus)
                .allMatch(OrderStatus::isTerminal);
    }

    /**
     * The batched fetch has to group correctly. Every order here carries exactly one line,
     * named after that order, so a line landing on the wrong order is unmissable.
     */
    @Test
    void batchedItemsAreAttachedToTheOrderTheyBelongTo() {
        List<Order> page = orderDao.findTerminalByUserId(userId, tenantId, 25, 0);

        assertThat(page).hasSize(25);
        for (Order order : page) {
            assertThat(order.getItems())
                    .as("order %s lost its lines when the fetch was batched", order.getTokenNo())
                    .hasSize(1);
            // token is H<runId>-<i>, and the item is named item-for-order-<i>
            String index = order.getTokenNo().substring(order.getTokenNo().lastIndexOf('-') + 1);
            assertThat(order.getItems().get(0).getItemNameSnapshot())
                    .as("order %s was given another order's line", order.getTokenNo())
                    .isEqualTo(itemNameFor(Integer.parseInt(index)));
        }
    }

    /** Newest first, or "show more" walks the history in an order nobody expects. */
    @Test
    void historyIsNewestFirstAcrossPages() {
        List<Order> all = orderDao.findTerminalByUserId(userId, tenantId, 25, 0);

        assertThat(all).extracting(Order::getCreatedAt)
                .isSortedAccordingTo((a, b) -> b.compareTo(a));
    }

    /** An empty page must not blow up the IN clause it would otherwise build. */
    @Test
    void anOffsetPastTheEndReturnsNothingRatherThanFailing() {
        assertThat(orderDao.findTerminalByUserId(userId, tenantId, 10, 500)).isEmpty();
    }
}
