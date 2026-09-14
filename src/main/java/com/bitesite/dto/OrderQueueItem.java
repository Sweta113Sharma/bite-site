package com.bitesite.dto;

import com.bitesite.model.Order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** JSON shape for the canteen live-queue poll — deliberately not the raw {@code Order}
 * entity, so internal fields (tenantId, userId, ...) never leak into the API response.
 *
 * <p>{@code itemSummaries} is what the kitchen still has to make. Removed lines are listed
 * separately so the card can show them struck through, and {@code lines} carries the ids
 * the "some items unavailable" checklist posts back. */
public record OrderQueueItem(Long id, String tokenNo, String status, BigDecimal totalAmount,
        LocalDateTime createdAt, List<String> itemSummaries, List<String> removedSummaries,
        List<Line> lines) {

    public record Line(Long id, int quantity, String name) {}

    public static OrderQueueItem from(Order order) {
        List<String> summaries = order.getItems().stream()
                .filter(i -> !i.isCancelled())
                .map(i -> i.getQuantity() + "x " + i.getItemNameSnapshot())
                .toList();
        List<String> removed = order.getItems().stream()
                .filter(i -> i.isCancelled())
                .map(i -> i.getQuantity() + "x " + i.getItemNameSnapshot())
                .toList();
        List<Line> lines = order.getItems().stream()
                .filter(i -> !i.isCancelled())
                .map(i -> new Line(i.getId(), i.getQuantity(), i.getItemNameSnapshot()))
                .toList();
        return new OrderQueueItem(order.getId(), order.getTokenNo(), order.getStatus().name(),
                order.getTotalAmount(), order.getCreatedAt(), summaries, removed, lines);
    }
}
