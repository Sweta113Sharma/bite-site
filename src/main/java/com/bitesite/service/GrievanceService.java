package com.bitesite.service;

import com.bitesite.dao.GrievanceDao;
import com.bitesite.dto.GrievanceAdminView;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.Grievance;
import com.bitesite.model.GrievanceStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** DPDP-2025-style grievance handling: students raise, the platform admin resolves. */
@Service
@RequiredArgsConstructor
public class GrievanceService {

    private final GrievanceDao grievanceDao;
    private final AuditService auditService;

    public Grievance raise(Long tenantId, Long userId, String subject, String message) {
        Grievance grievance = Grievance.builder()
                .tenantId(tenantId)
                .raisedByUserId(userId)
                .subject(subject)
                .message(message)
                .status(GrievanceStatus.OPEN)
                .build();
        return grievanceDao.save(grievance);
    }

    public List<Grievance> listForUser(Long userId, Long tenantId) {
        return grievanceDao.findByUserIdAndTenantId(userId, tenantId);
    }

    public List<GrievanceAdminView> listAll() {
        return grievanceDao.findAllWithDetails();
    }

    /** Admin-side resolve, looked up by id alone (see {@link GrievanceDao#findById}). */
    public void resolve(Long id, String response, Long actorUserId) {
        Grievance before = grievanceDao.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Grievance not found"));
        grievanceDao.resolve(id, before.getTenantId(), response, GrievanceStatus.RESOLVED);
        auditService.record(actorUserId, before.getTenantId(), "Grievance", id, "RESOLVE",
                before.getStatus(), GrievanceStatus.RESOLVED);
    }
}
