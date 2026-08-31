package com.bitesite.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A menu section, owned by one outlet.
 *
 * <p>Scoped per outlet rather than per tenant on purpose: two canteens at the same college
 * each keep their own "Snacks" instead of being made to share one, which would mean either
 * of them could rename the other's section.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Category {
    private Long id;
    private Long tenantId;
    private Long outletId;
    private String name;

    /** Explicit display order — a canteen puts mains before drinks whatever the spelling. */
    private int sortOrder;

    private LocalDateTime createdAt;

    /** Populated on listing screens so a manager can see what deleting would affect. */
    private int itemCount;
}
