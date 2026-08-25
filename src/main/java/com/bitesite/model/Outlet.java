package com.bitesite.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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

    private LocalDateTime createdAt;
}
