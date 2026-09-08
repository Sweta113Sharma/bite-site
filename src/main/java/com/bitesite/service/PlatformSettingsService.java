package com.bitesite.service;

import com.bitesite.dao.PlatformSettingsDao;
import com.bitesite.model.GrievanceOfficer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Platform-wide settings, as opposed to {@link TechConfigService}'s per-tenant ones.
 *
 * <p>Currently just the published grievance officer. Kept narrow on purpose: the table
 * behind it is a generic key/value store, but exposing a generic {@code set(key, value)}
 * would let any caller publish an arbitrary string onto a legal page, and the whole point
 * of {@link GrievanceOfficer#isComplete()} is that the officer is written and read as one
 * whole thing rather than five loose keys.
 */
@Service
@RequiredArgsConstructor
public class PlatformSettingsService {

    private final PlatformSettingsDao platformSettingsDao;
    private final AuditService auditService;

    public GrievanceOfficer getGrievanceOfficer() {
        Map<String, String> s = platformSettingsDao.findAll();
        return new GrievanceOfficer(
                s.get(GrievanceOfficer.KEY_NAME),
                s.get(GrievanceOfficer.KEY_DESIGNATION),
                s.get(GrievanceOfficer.KEY_EMAIL),
                s.get(GrievanceOfficer.KEY_ADDRESS),
                s.get(GrievanceOfficer.KEY_RESPONSE_WINDOW));
    }

    /**
     * Publishes the officer. Audited with both states, because this is the contact a
     * regulator or a complainant is told to use: who changed it, and to what, is exactly
     * the question that gets asked later.
     *
     * <p>tenantId is null on the audit row — the platform is not a tenant, and audit_log
     * allows it.
     */
    public void saveGrievanceOfficer(GrievanceOfficer officer, Long actorUserId) {
        GrievanceOfficer before = getGrievanceOfficer();
        platformSettingsDao.upsert(GrievanceOfficer.KEY_NAME, officer.name());
        platformSettingsDao.upsert(GrievanceOfficer.KEY_DESIGNATION, officer.designation());
        platformSettingsDao.upsert(GrievanceOfficer.KEY_EMAIL, officer.email());
        platformSettingsDao.upsert(GrievanceOfficer.KEY_ADDRESS, officer.address());
        platformSettingsDao.upsert(GrievanceOfficer.KEY_RESPONSE_WINDOW, officer.responseWindow());
        auditService.record(actorUserId, null, "GrievanceOfficer", null, "UPDATE", before, officer);
    }
}
