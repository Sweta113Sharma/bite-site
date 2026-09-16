package com.bitesite.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * The platform's artwork for a category NAME, set by an admin and shared by every outlet
 * whose category happens to be called that.
 *
 * <p>Name-keyed and deliberately not tenant-scoped: this is the rung that lets a brand new
 * canteen's "Beverages" look right on its first day, before anyone there has uploaded
 * anything. An outlet that does upload its own image overrides this — see
 * {@link CategoryImage}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryDefaultImage {

    /**
     * The match key: the category name lowercased and trimmed. Normalisation lives in one
     * place ({@code CategoryImageService.normalise}) and is applied on write and on read,
     * so a lookup can never miss a row that a different spelling created.
     */
    private String nameKey;

    /** The name as a human first typed it, for display on the admin list only. */
    private String displayName;

    private String imagePath;

    /** The admin who set it. Null once that account has been deleted. */
    private Long uploadedBy;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
