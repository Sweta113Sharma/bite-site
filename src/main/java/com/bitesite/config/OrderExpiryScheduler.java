package com.bitesite.config;

import com.bitesite.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Marks unpaid orders EXPIRED once they've sat past the payment timeout, so the canteen
 * queue and any "pending demand" views never carry orders that were never going to be paid. */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderExpiryScheduler {

    private final OrderService orderService;

    @Value("${app.order.payment-timeout-minutes}")
    private int timeoutMinutes;

    @Scheduled(fixedDelay = 60_000)
    public void expireStalePayments() {
        int count = orderService.expireStalePayments(timeoutMinutes);
        if (count > 0) {
            log.info("Expired {} unpaid order(s) past the {}-minute payment timeout", count, timeoutMinutes);
        }
    }
}
