package com.bitesite.service;

import com.bitesite.dao.OrderDao;
import com.bitesite.model.Settlement;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * What each canteen is owed for a period, and what the platform kept.
 *
 * <p>Every rupee lands in one Razorpay account, so this is a payout ledger rather than an
 * invoice: the platform holds the money and owes the canteen the rest of it.
 *
 * <p><b>It sums what each order recorded, and never recalculates.</b> The commission on a
 * March order is the rate that order was placed under, even if the canteen renegotiated in
 * April. Recomputing from today's rate would quietly restate every past payout the day
 * terms changed, which is the kind of error nobody notices until a canteen disputes a
 * number and both sides are right about different months.
 *
 * <p>Only orders that were actually paid for count. An abandoned or refunded order moved
 * no money and must not appear in a payout.
 */
@Service
@RequiredArgsConstructor
public class SettlementService {

    private final OrderDao orderDao;

    /** The periods the report offers, so a screen never has to invent date arithmetic. */
    public enum Period {
        TODAY("Today"),
        SEVEN_DAYS("Last 7 days"),
        THREE_WEEKS("Last 3 weeks"),
        THIRTY_DAYS("Last 30 days"),
        THIS_MONTH("This month"),
        ALL_TIME("All time");

        private final String label;

        Period(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        /** Inclusive start, or null for all time. */
        public LocalDateTime from(LocalDate today) {
            return switch (this) {
                case TODAY -> today.atStartOfDay();
                case SEVEN_DAYS -> today.minusDays(6).atStartOfDay();
                // Three weeks counted as 21 days ending today, not three calendar weeks:
                // it is asked as "the last three weeks", which is a rolling window.
                case THREE_WEEKS -> today.minusDays(20).atStartOfDay();
                case THIRTY_DAYS -> today.minusDays(29).atStartOfDay();
                case THIS_MONTH -> today.withDayOfMonth(1).atStartOfDay();
                case ALL_TIME -> null;
            };
        }
    }

    /** One canteen's position for the period. */
    public List<Settlement> forPeriod(Period period, Long tenantId, LocalDate today) {
        return orderDao.settlementByOutlet(period.from(today), tenantId);
    }
}
