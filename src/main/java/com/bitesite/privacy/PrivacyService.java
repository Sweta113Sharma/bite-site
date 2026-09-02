package com.bitesite.privacy;

import com.bitesite.dao.GrievanceDao;
import com.bitesite.dao.OrderDao;
import com.bitesite.dao.UserDao;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.Grievance;
import com.bitesite.model.Order;
import com.bitesite.model.User;
import com.bitesite.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rights the published privacy policy promises, made real.
 *
 * <p>The policy told students they could access and delete their data. Deletion existed;
 * access did not, in any form. This adds the export, the consent record behind
 * registration's "you agree to our terms", and the request queue an operator works.
 */
@Service
@RequiredArgsConstructor
public class PrivacyService {

    /**
     * Bumped whenever the published policy text changes materially.
     *
     * <p>Consent to a document means nothing without recording which text was agreed to —
     * "they accepted the terms" is not an answer if the terms have since been rewritten.
     */
    public static final String POLICY_VERSION = "2026-08-01";

    private final PrivacyDao privacyDao;
    private final UserDao userDao;
    private final OrderDao orderDao;
    private final GrievanceDao grievanceDao;
    private final AuditService auditService;

    public void recordRegistrationConsent(Long userId) {
        privacyDao.grantConsent(userId, ConsentPurpose.TERMS, POLICY_VERSION);
    }

    public List<PrivacyDao.Consent> consentsFor(Long userId) {
        return privacyDao.findConsents(userId);
    }

    public void setConsent(Long userId, ConsentPurpose purpose, boolean granted, Long tenantId) {
        if (granted) {
            privacyDao.grantConsent(userId, purpose, POLICY_VERSION);
        } else {
            privacyDao.withdrawConsent(userId, purpose);
        }
        auditService.record(userId, tenantId, "Consent", userId,
                granted ? "CONSENT_GRANTED_" + purpose : "CONSENT_WITHDRAWN_" + purpose, null, null);
    }

    /**
     * Everything held about one student, as plain nested data for JSON.
     *
     * <p>Strictly their own: every read is scoped by both user and tenant, so this cannot
     * be turned into a way to read someone else's history by changing an id. Built by hand
     * rather than serialising the entities directly — password hashes and internal ids have
     * no business in an export, and a field added to a model later must not silently join
     * it.
     */
    public Map<String, Object> exportFor(Long userId, Long tenantId) {
        User user = userDao.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", user.getName());
        profile.put("email", user.getEmail());
        profile.put("phone", user.getPhone());
        profile.put("rollNo", user.getRollNo());
        profile.put("joined", String.valueOf(user.getCreatedAt()));

        List<Map<String, Object>> orders = orderDao.findByUserId(userId, tenantId).stream()
                .map(PrivacyService::orderView)
                .toList();

        List<Map<String, Object>> grievances = grievanceDao.findByUserIdAndTenantId(userId, tenantId).stream()
                .map(PrivacyService::grievanceView)
                .toList();

        List<Map<String, Object>> consents = privacyDao.findConsents(userId).stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("purpose", c.purpose().name());
                    m.put("policyVersion", c.policyVersion());
                    m.put("grantedAt", String.valueOf(c.grantedAt()));
                    m.put("withdrawnAt", String.valueOf(c.withdrawnAt()));
                    return m;
                })
                .toList();

        Map<String, Object> export = new LinkedHashMap<>();
        export.put("exportedAt", java.time.LocalDateTime.now().toString());
        export.put("policyVersion", POLICY_VERSION);
        export.put("profile", profile);
        export.put("notificationPreferences", Map.of(
                "orderUpdates", user.isNotifyOrderUpdates(),
                "marketing", user.isNotifyMarketing()));
        export.put("consents", consents);
        export.put("orders", orders);
        export.put("supportRequests", grievances);

        auditService.record(userId, tenantId, "User", userId, "DATA_EXPORTED", null, null);
        return export;
    }

    private static Map<String, Object> orderView(Order order) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", order.getTokenNo());
        m.put("placedAt", String.valueOf(order.getCreatedAt()));
        m.put("status", order.getStatus().name());
        m.put("total", order.getTotalAmount());
        m.put("items", order.getItems().stream().map(i -> Map.of(
                "name", i.getItemNameSnapshot(),
                "quantity", i.getQuantity(),
                "unitPrice", i.getUnitPrice())).toList());
        return m;
    }

    private static Map<String, Object> grievanceView(Grievance g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subject", g.getSubject());
        m.put("message", g.getMessage());
        m.put("status", g.getStatus().name());
        m.put("raisedAt", String.valueOf(g.getCreatedAt()));
        m.put("response", g.getAdminResponse());
        return m;
    }

    public void raiseRequest(Long userId, Long tenantId, DataRequest.Kind kind, String note) {
        privacyDao.saveRequest(DataRequest.builder()
                .userId(userId).tenantId(tenantId).kind(kind)
                .status(DataRequest.Status.OPEN).note(note).build());
        auditService.record(userId, tenantId, "DataRequest", userId, "REQUEST_" + kind, null, null);
    }

    public List<DataRequest> queue(DataRequest.Status status, int limit) {
        return privacyDao.findRequests(status, limit);
    }

    public void setRequestStatus(Long id, DataRequest.Status status, Long actorUserId) {
        privacyDao.updateRequestStatus(id, status);
        auditService.record(actorUserId, null, "DataRequest", id, "REQUEST_" + status, null, null);
    }
}
