package com.bitesite.dto;

import com.bitesite.model.Order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** JSON shape for the canteen live-queue poll — deliberately not the raw {@code Order}
 * entity, so internal fields (tenantId, userId, ...) never leak into the API response. */
public record OrderQueueItem(Long id, String tokenNo, String status, BigDecimal totalAmount,
        LocalDateTime createdAt, List<String> itemSummaries) {

    public static OrderQueueItem from(Order order) {
        List<String> summaries = order.getItems().stream()
                .map(i -> i.getQuantity() + "x " + i.getItemNameSnapshot())
                .toList();
        return new OrderQueueItem(order.getId(), order.getTokenNo(), order.getStatus().name(),
                order.getTotalAmount(), order.getCreatedAt(), summaries);
    }
}
