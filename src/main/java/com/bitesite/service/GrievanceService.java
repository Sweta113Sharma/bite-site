package com.bitesite.service;

import com.bitesite.dao.GrievanceDao;
import com.bitesite.dao.OrderDao;
import com.bitesite.dto.GrievanceAdminView;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.Grievance;
import com.bitesite.model.GrievanceStatus;
import com.bitesite.model.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** DPDP-2025-style grievance handling: students raise, the platform admin resolves. */
@Service
@RequiredArgsConstructor
public class GrievanceService {

    private final GrievanceDao grievanceDao;
    private final OrderDao orderDao;
    private final AuditService auditService;

    /**
     * Raises a ticket, optionally against one of the caller's own orders.
     *
     * <p>The orderId arrives from a form field, so it is re-checked here rather than
     * trusted: it must resolve inside the caller's tenant and belong to the caller.
     * Without that a student could attach someone else's order to their own ticket and
     * read its token, total and status back off the support screen.
     */
    public Grievance raise(Long tenantId, Long userId, Long orderId, String subject, String message) {
        if (orderId != null) {
            Order order = orderDao.findByIdAndTenantId(orderId, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
            if (!order.getUserId().equals(userId)) {
                throw new ResourceNotFoundException("Order not found");
            }
        }
        Grievance grievance = Grievance.builder()
                .tenantId(tenantId)
                .raisedByUserId(userId)
                .orderId(orderId)
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
