package com.bitesite.dao;

import com.bitesite.model.Category;

import java.util.List;
import java.util.Optional;

public interface CategoryDao {

    /** One outlet's categories in display order, each carrying how many items use it. */
    List<Category> findByOutlet(Long outletId, Long tenantId);

    Optional<Category> findByIdAndTenantId(Long id, Long tenantId);

    Category save(Category category);

    /** How many menu items point at this category. Callers refuse to delete a non-empty
     * one rather than orphan or silently move its items. */
    int countItems(Long categoryId);

    void delete(Long id, Long tenantId);
}
