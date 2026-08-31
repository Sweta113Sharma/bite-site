package com.bitesite.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Outlet {
    private Long id;
    private Long tenantId;
    private String name;

    /** Admin-controlled. An inactive outlet disappears from the student app entirely. */
    private boolean active;

    /**
     * Outlet-controlled, and a different question from {@link #active}: "are we taking
     * new orders right this minute?". Staff flip this themselves when the kitchen is
     * swamped or they are closing early; the outlet stays live, its queue stays workable,
     * and students see a "not taking orders right now" state instead of the canteen
     * vanishing from the picker.
     */
    @Builder.Default
    private boolean acceptingOrders = true;

    /** Stated opening hours. Null means the outlet has not published any, which is shown
     * as "hours not set" rather than as a closed canteen. */
    private LocalTime opensAt;
    private LocalTime closesAt;

    private String contactPhone;

    /** A short free-text line students see on the menu — "closed for the festival", that
     * kind of thing. Distinct from pausing orders, which actually blocks checkout. */
    private String notice;

    private LocalDateTime createdAt;

    /** True only when both hours are set and now falls inside them. Used for display; it
     * deliberately does not gate ordering, which acceptingOrders and isActive do. */
    public boolean withinStatedHours(LocalTime now) {
        if (opensAt == null || closesAt == null) {
            return true;
        }
        // A window that ends before it starts runs past midnight.
        return closesAt.isAfter(opensAt)
                ? !now.isBefore(opensAt) && now.isBefore(closesAt)
                : !now.isBefore(opensAt) || now.isBefore(closesAt);
    }
}
