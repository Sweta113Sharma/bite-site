package com.bitesite.service;

import com.bitesite.dao.CategoryDao;
import com.bitesite.exception.BusinessException;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.Category;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryDao categoryDao;
    private final AuditService auditService;

    public List<Category> listForOutlet(Long outletId, Long tenantId) {
        return categoryDao.findByOutlet(outletId, tenantId);
    }

    public Category get(Long id, Long tenantId) {
        return categoryDao.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
    }

    public Category create(Long tenantId, Long outletId, String name, Long actorUserId) {
        String trimmed = requireName(name);
        // New sections go to the end rather than the top: an existing menu's order is
        // something the manager arranged, and a new item should not displace it.
        int next = listForOutlet(outletId, tenantId).stream()
                .mapToInt(Category::getSortOrder).max().orElse(0) + 1;
        Category saved = categoryDao.save(Category.builder()
                .tenantId(tenantId).outletId(outletId).name(trimmed).sortOrder(next).build());
        auditService.record(actorUserId, tenantId, "Category", saved.getId(), "CREATE", null, saved);
        return saved;
    }

    /** Renaming reaches every item in the section at once, which is the whole point —
     * previously the name lived on each row and had to be edited item by item. */
    public void rename(Long id, Long tenantId, String name, Long actorUserId) {
        Category before = get(id, tenantId);
        String trimmed = requireName(name);
        categoryDao.save(Category.builder()
                .id(id).tenantId(tenantId).outletId(before.getOutletId())
                .name(trimmed).sortOrder(before.getSortOrder()).build());
        auditService.record(actorUserId, tenantId, "Category", id, "RENAME", before.getName(), trimmed);
    }

    public void reorder(Long id, Long tenantId, int sortOrder, Long actorUserId) {
        Category before = get(id, tenantId);
        categoryDao.save(Category.builder()
                .id(id).tenantId(tenantId).outletId(before.getOutletId())
                .name(before.getName()).sortOrder(sortOrder).build());
        auditService.record(actorUserId, tenantId, "Category", id, "REORDER",
                before.getSortOrder(), sortOrder);
    }

    /**
     * Deletes an empty category.
     *
     * <p>Refused while items still point at it. The alternatives are worse: a cascade
     * would silently delete a section's worth of menu, and reassigning to some default
     * would quietly reshuffle the student-facing menu without anyone asking for it. The
     * manager moves the items first, which is a decision only they can make.
     */
    public void delete(Long id, Long tenantId, Long actorUserId) {
        Category category = get(id, tenantId);
        int items = categoryDao.countItems(id);
        if (items > 0) {
            throw new BusinessException(category.getName() + " still has " + items + " item"
                    + (items == 1 ? "" : "s") + " in it. Move them to another category first.");
        }
        categoryDao.delete(id, tenantId);
        auditService.record(actorUserId, tenantId, "Category", id, "DELETE", category, null);
    }

    private String requireName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException("A category needs a name.");
        }
        return trimmed;
    }
}
