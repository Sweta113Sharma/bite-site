package com.bitesite.dto;

import java.math.BigDecimal;

/**
 * The three insight blocks on the admin home screen.
 *
 * <p>Each is a small read-only aggregate computed in SQL over today's orders — no
 * machine learning, no materialised tables, just the numbers a canteen operator would
 * otherwise have to eyeball from the order queue.
 */
public record AdminInsights(
        java.util.List<SellOutAlert> sellOuts,
        java.util.List<PopularItem> popularItems,
        java.util.List<PeakHour> peakHours) {

    /** An item that hit its {@code daily_limit} today (or sold out with none set). */
    public record SellOutAlert(long menuItemId, String name, String outletName,
            long soldToday, Long dailyLimit) {}

    /** The most-ordered items today, by quantity. */
    public record PopularItem(long menuItemId, String name, String outletName,
            long soldToday, BigDecimal revenue) {}

    /** Orders per hour of the day, for the peak-hour heatmap. */
    public record PeakHour(int hour, long orderCount) {}
}
