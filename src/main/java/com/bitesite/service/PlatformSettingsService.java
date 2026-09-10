package com.bitesite.service;

import com.bitesite.dao.PlatformSettingsDao;
import com.bitesite.model.BillingSettings;
import com.bitesite.model.GrievanceOfficer;
import com.bitesite.model.OrderSettings;
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
    /**
     * Saves the platform's commercial settings.
     *
     * <p>Audited as one change with the whole before and after, because these decide what
     * students are charged and what canteens are owed. "Who set the commission to 8% and
     * when" is a question that will eventually be asked, and the audit row is the answer.
     *
     * <p>Only affects orders placed after it. Existing orders carry their own terms — see
     * {@link BillingService}.
     */
    public void saveBillingSettings(Map<String, String> values, Long actorUserId) {
        BillingSettings before = BillingSettings.from(platformSettingsDao.findAll());
        values.forEach(platformSettingsDao::upsert);
        BillingSettings after = BillingSettings.from(platformSettingsDao.findAll());
        auditService.record(actorUserId, null, "BillingSettings", null, "UPDATE", before, after);
    }

    public OrderSettings getOrderSettings() {
        return OrderSettings.from(platformSettingsDao.findAll());
    }

    /**
     * Saves how long a student may undo their own order.
     *
     * <p>Clamped here rather than trusted from the form, because this number is also how
     * long the kitchen is kept from seeing a paid order: a stray digit would leave students
     * waiting on food nobody had been told to cook. The caller is told what was actually
     * stored, so a clamped value is visible rather than silent.
     *
     * <p>Audited like the billing terms. It decides whether a refund is owed, so "who
     * shortened the window, and when" is a question a disputed order will eventually ask.
     *
     * <p>Read fresh on every order, so a change takes effect immediately — including on
     * orders already paid for and still inside the old window. See the admin screen.
     *
     * @return the window as stored, after clamping
     */
    public int saveSelfCancelWindow(int requestedSeconds, Long actorUserId) {
        OrderSettings before = getOrderSettings();
        int seconds = OrderSettings.clampWindow(requestedSeconds);
        platformSettingsDao.upsert(OrderSettings.SELF_CANCEL_WINDOW, String.valueOf(seconds));
        OrderSettings after = getOrderSettings();
        auditService.record(actorUserId, null, "OrderSettings", null, "UPDATE", before, after);
        return after.selfCancelWindowSeconds();
    }

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
