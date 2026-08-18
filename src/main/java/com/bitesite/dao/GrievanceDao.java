package com.bitesite.dao;

import com.bitesite.dto.GrievanceAdminView;
import com.bitesite.model.Grievance;
import com.bitesite.model.GrievanceStatus;

import java.util.List;
import java.util.Optional;

public interface GrievanceDao {
    Grievance save(Grievance grievance);

    Optional<Grievance> findByIdAndTenantId(Long id, Long tenantId);

    /** Platform-admin lookup, deliberately not tenant-scoped — SUPER_ADMIN manages
     * grievances across every college by design. */
    Optional<Grievance> findById(Long id);

    /** For the admin's cross-college grievance inbox — no tenant scoping, joined with
     * college/student names so the inbox is actually readable. */
    List<GrievanceAdminView> findAllWithDetails();

    List<Grievance> findByTenantId(Long tenantId);

    List<Grievance> findByUserIdAndTenantId(Long userId, Long tenantId);

    void resolve(Long id, Long tenantId, String adminResponse, GrievanceStatus status);
}
