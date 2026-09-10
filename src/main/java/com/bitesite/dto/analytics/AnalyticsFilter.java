package com.bitesite.dto.analytics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Filter parameters for analytics aggregation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsFilter {

    /**
     * Preset range: "today", "yesterday", "7d" (default), "30d", "mtd", or "custom".
     */
    @Builder.Default
    private String range = "7d";

    private LocalDate fromDate;
    private LocalDate toDate;

    /**
     * Optional tenant (college) filter. Null means all colleges.
     */
    private Long tenantId;

    /**
     * Optional outlet (canteen) filter. Null means all canteens within the scope.
     */
    private Long outletId;
}
