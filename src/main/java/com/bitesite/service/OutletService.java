package com.bitesite.service;

import com.bitesite.dao.OutletDao;
import com.bitesite.dao.UserDao;
import com.bitesite.exception.BusinessException;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.Outlet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutletService {

    private final OutletDao outletDao;
    private final UserDao userDao;
    private final AuditService auditService;
    private final FileStorageService fileStorageService;

    public List<Outlet> listActive(Long tenantId) {
        return outletDao.findActiveByTenantId(tenantId);
    }

    public List<Outlet> listAll(Long tenantId) {
        return outletDao.findByTenantId(tenantId);
    }

    public Outlet get(Long id, Long tenantId) {
        return outletDao.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Outlet not found"));
    }

    public Outlet create(Long tenantId, String name) {
        return outletDao.save(Outlet.builder().tenantId(tenantId).name(name).active(true).build());
    }

    public Outlet rename(Long id, Long tenantId, String name, Long actorUserId) {
        Outlet before = get(id, tenantId);
        Outlet saved = outletDao.save(Outlet.builder()
                .id(id)
                .tenantId(tenantId)
                .name(name)
                .active(before.isActive())
                .acceptingOrders(before.isAcceptingOrders())
                .build());
        auditService.record(actorUserId, tenantId, "Outlet", id, "RENAME", before.getName(), name);
        return saved;
    }

    /**
     * Admin enable/disable. A deactivated outlet leaves the student app completely — it
     * stops appearing in the canteen picker and its menu becomes unreachable — while every
     * order, payment and audit record it ever produced stays exactly where it is. This is
     * the answer to almost every "remove this canteen" request; {@link #delete} is for the
     * narrower case of one created by mistake.
     */
    public void setActive(Long id, Long tenantId, boolean active, Long actorUserId) {
        Outlet before = get(id, tenantId);
        outletDao.save(Outlet.builder()
                .id(id)
                .tenantId(tenantId)
                .name(before.getName())
                .active(active)
                .acceptingOrders(before.isAcceptingOrders())
                .build());
        auditService.record(actorUserId, tenantId, "Outlet", id,
                active ? "ACTIVATE" : "DEACTIVATE", before.isActive(), active);
    }

    /**
     * Opening hours, contact and notice, set by the outlet's own manager.
     *
     * <p>Goes through updateSettings rather than save() so it cannot touch isActive or
     * acceptingOrders: a manager editing their hours must not be able to reactivate a
     * canteen the platform admin disabled, which a full-object save would happily do.
     */
    public void updateSettings(Long id, Long tenantId, java.time.LocalTime opensAt,
            java.time.LocalTime closesAt, String contactPhone, String notice,
            java.math.BigDecimal latitude, java.math.BigDecimal longitude, Long actorUserId) {
        Outlet before = get(id, tenantId);
        outletDao.updateSettings(id, tenantId, opensAt, closesAt,
                blankToNull(contactPhone), blankToNull(notice), latitude, longitude);
        auditService.record(actorUserId, tenantId, "Outlet", id, "UPDATE_SETTINGS", before, get(id, tenantId));
    }

    /**
     * The canteen's own logo, set by its manager from the same settings form as the hours.
     *
     * <p>Same three-way rule as a menu item's photo: a new file replaces whatever was
     * there, ticking "remove" drops back to the storefront glyph, and leaving both alone
     * keeps the current logo — an empty file input is "I didn't touch this", not "delete
     * it". Returns without touching the row in that last case, so saving unrelated
     * settings does not write an audit entry for a logo that did not change.
     */
    public void updateLogo(Long id, Long tenantId, MultipartFile logo, boolean removeLogo, Long actorUserId) {
        Outlet before = get(id, tenantId);
        String path;
        if (logo != null && !logo.isEmpty()) {
            path = fileStorageService.storeOutletLogo(tenantId, id, logo);
        } else if (removeLogo && before.getLogoPath() != null) {
            path = null;
        } else {
            return;
        }
        outletDao.updateLogoPath(id, tenantId, path);
        auditService.record(actorUserId, tenantId, "Outlet", id,
                path == null ? "LOGO_REMOVE" : "LOGO_UPLOAD", before.getLogoPath(), path);
    }

    /** An empty form field means "not set", not an empty string — otherwise the templates
     * would have to distinguish null from "" everywhere they show these. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** The outlet's own "we're taking orders / we're paused" switch. */
    public void setAcceptingOrders(Long id, Long tenantId, boolean acceptingOrders, Long actorUserId) {
        Outlet before = get(id, tenantId);
        outletDao.updateAcceptingOrders(id, tenantId, acceptingOrders);
        auditService.record(actorUserId, tenantId, "Outlet", id,
                acceptingOrders ? "RESUME_ORDERS" : "PAUSE_ORDERS", before.isAcceptingOrders(), acceptingOrders);
    }

    /**
     * A canteen's commercial terms: its cut, and the registration the student's invoice is
     * issued against.
     *
     * <p>A blank commission is stored as null rather than zero, because the two mean
     * different things: null follows the platform default wherever it moves, zero is a
     * deliberately negotiated free ride that must survive the default changing.
     *
     * <p>Audited — this decides what the canteen is paid.
     */
    public void updateCommercialTerms(Long id, Long tenantId, BigDecimal commissionPercent,
            String gstin, String legalName, Long actorUserId) {
        Outlet before = get(id, tenantId);
        if (commissionPercent != null
                && (commissionPercent.compareTo(BigDecimal.ZERO) < 0
                    || commissionPercent.compareTo(new BigDecimal("100")) > 0)) {
            throw new BusinessException("Commission must be between 0 and 100 percent.");
        }
        String cleanedGstin = gstin == null || gstin.isBlank() ? null : gstin.trim().toUpperCase();
        if (cleanedGstin != null && cleanedGstin.length() != 15) {
            throw new BusinessException("A GSTIN is 15 characters. Leave it blank if the canteen is not registered.");
        }
        outletDao.updateCommercialTerms(id, tenantId, commissionPercent, cleanedGstin,
                legalName == null || legalName.isBlank() ? null : legalName.trim());
        auditService.record(actorUserId, tenantId, "Outlet", id, "COMMERCIAL_TERMS",
                before.getCommissionPercent(), commissionPercent);
    }

    /**
     * Permanently removes an outlet, its menu, and its staff accounts' access.
     *
     * <p>Refused outright once even one order has been placed there. Orders and payments
     * are financial records with a retention obligation behind them, and they point at
     * this row; there is no version of "delete the canteen but keep the receipts" that
     * leaves the data honest. Deactivation does everything an admin actually wants in that
     * case, so that is what the caller is told to use.
     *
     * <p>Staff accounts are detached and switched off rather than deleted, so the audit
     * log's "who did this" still resolves to a person after the outlet is gone.
     *
     * @return how many staff accounts were switched off
     */
    @Transactional
    public int delete(Long id, Long tenantId, Long actorUserId) {
        Outlet outlet = get(id, tenantId);

        int orders = outletDao.countOrders(id);
        if (orders > 0) {
            throw new BusinessException(outlet.getName() + " has " + orders + " order"
                    + (orders == 1 ? "" : "s") + " on record and cannot be deleted — those are financial "
                    + "records tied to it. Deactivate it instead: it disappears from the student app and "
                    + "stops taking orders, but its history stays intact.");
        }

        List<Long> staffIds = outletDao.findStaffUserIds(id);
        staffIds.forEach(userDao::detachFromOutlet);
        outletDao.delete(id, tenantId);

        auditService.record(actorUserId, tenantId, "Outlet", id, "DELETE", outlet, null);
        log.warn("Outlet {} ({}) deleted from tenant {} by user {}; {} staff account(s) deactivated",
                id, outlet.getName(), tenantId, actorUserId, staffIds.size());
        return staffIds.size();
    }
}
