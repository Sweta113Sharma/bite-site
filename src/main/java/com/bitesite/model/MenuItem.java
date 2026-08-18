package com.bitesite.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MenuItem {
    private Long id;
    private Long tenantId;
    private Long outletId;
    private String name;
    private String category;
    private String photoPath;
    private BigDecimal price;
    private BigDecimal discountPrice;
    private BigDecimal discountPercent;
    private boolean available;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * A flat discount price always wins over a percentage discount when both are set,
     * since staff typically set one or the other, not both.
     */
    public BigDecimal effectivePrice() {
        if (discountPrice != null) {
            return discountPrice;
        }
        if (discountPercent != null && discountPercent.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal factor = BigDecimal.ONE.subtract(discountPercent.divide(BigDecimal.valueOf(100)));
            return price.multiply(factor).setScale(2, RoundingMode.HALF_UP);
        }
        return price;
    }

    public boolean hasDiscount() {
        return effectivePrice().compareTo(price) < 0;
    }
}
