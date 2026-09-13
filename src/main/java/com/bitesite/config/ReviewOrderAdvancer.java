package com.bitesite.config;

import com.bitesite.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Advances review-account orders through the happy path so Google Play reviewers
 * can verify the full order lifecycle without canteen staff being online.
 *
 * <p>Each tick moves every eligible order one step forward; the full journey
 * (PAID → PREPARING → READY_FOR_PICKUP → COMPLETED) therefore takes roughly
 * three ticks, or about 90 seconds at the default 30-second interval.
 *
 * <p>When no review accounts exist — normal production — the query returns zero
 * rows and this is a no-op.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReviewOrderAdvancer {

    private final OrderService orderService;

    @Scheduled(fixedDelay = 30_000)
    public void advanceReviewOrders() {
        int count = orderService.advanceReviewOrders();
        if (count > 0) {
            log.info("Auto-advanced {} review-account order(s)", count);
        }
    }
}
