package com.bitesite.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class MenuItemForm implements Serializable {

    @NotBlank(message = "Name is required")
    private String name;

    @NotBlank(message = "Category is required")
    private String category;

    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.0", message = "Price cannot be negative")
    private BigDecimal price;

    @DecimalMin(value = "0.0", message = "Discount price cannot be negative")
    private BigDecimal discountPrice;

    @DecimalMin(value = "0.0", message = "Discount percent cannot be negative")
    @DecimalMax(value = "100.0", message = "Discount percent cannot exceed 100")
    private BigDecimal discountPercent;

    /**
     * How many of this item the kitchen can serve in a day. Left blank for the usual case
     * of "as many as people order"; the upper bound only exists so a typo can't set a cap
     * so large it is indistinguishable from no cap while still costing a check on it.
     */
    @Min(value = 1, message = "A daily limit must be at least 1 — leave it blank for no limit")
    @Max(value = 100000, message = "That daily limit is too large — leave it blank for no limit")
    private Integer dailyLimit;

    /** Ticked on the edit form to drop the uploaded photo and fall back to the illustration. */
    private boolean removePhoto;
}
