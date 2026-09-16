package com.bitesite.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One outlet's own artwork for one of its categories.
 *
 * <p>Keyed by the category itself rather than by name: a category row belongs to exactly
 * one outlet (see {@code uq_categories_outlet_name}), so this is a genuine one-to-one and
 * the database enforces it. The platform-wide fallback is the other shape entirely — keyed
 * on a normalised name so one upload can cover every outlet that uses it — and lives in
 * {@link CategoryDefaultImage}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryImage {

    /** Also the primary key. There is at most one image per category. */
    private Long categoryId;

    private Long tenantId;
    private Long outletId;

    /** A path or URL exactly as {@code FileStorageService} returned it, rendered as-is. */
    private String imagePath;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
