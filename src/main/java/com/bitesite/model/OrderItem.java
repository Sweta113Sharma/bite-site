package com.bitesite.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItem {
    private Long id;
    private Long orderId;
    private Long menuItemId;
    private String itemNameSnapshot;
    private int quantity;
    private BigDecimal unitPrice;
    private BigDecimal subtotal;
}
