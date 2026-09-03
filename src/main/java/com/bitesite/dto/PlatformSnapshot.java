package com.bitesite.dto;

import java.math.BigDecimal;

/**
 * The numbers on the admin home screen.
 *
 * <p>Chosen for actionability rather than completeness: each one either tells you the
 * platform is working, or points at something a person has to go and do. "Orders today"
 * and "revenue today" answer is-it-working; the other four are queues with people waiting
 * at the end of them.
 */
public record PlatformSnapshot(
        long ordersToday,
        BigDecimal revenueToday,
        long ordersInFlight,
        long failedPaymentsToday,
        long openGrievances,
        long openDataRequests,
        long activeColleges,
        long activeCanteens) {
}
