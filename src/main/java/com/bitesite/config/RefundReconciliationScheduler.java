package com.bitesite.config;

import com.bitesite.service.RefundReconciliationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Settles refunds whose outcome the synchronous call never delivered and the webhook did
 * not cover, by asking Razorpay. See {@link RefundReconciliationService}. */
@Component
@RequiredArgsConstructor
@Slf4j
public class RefundReconciliationScheduler {

    private final RefundReconciliationService refundReconciliationService;

    @Value("${app.refund.reconcile-after-minutes}")
    private int reconcileAfterMinutes;

    @Scheduled(fixedDelay = 5 * 60_000)
    public void reconcilePendingRefunds() {
        int settled = refundReconciliationService.reconcilePending(reconcileAfterMinutes);
        if (settled > 0) {
            log.info("Settled {} refund(s) that had been pending for over {} minutes", settled, reconcileAfterMinutes);
        }
    }
}
