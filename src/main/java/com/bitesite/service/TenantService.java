package com.bitesite.service;

import com.bitesite.exception.BusinessException;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.tenant.Tenant;
import com.bitesite.tenant.TenantDao;
import com.bitesite.tenant.TenantStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class TenantService {

    private final TenantDao tenantDao;
    private final FileStorageService fileStorageService;
    private final AuditService auditService;

    public List<Tenant> listAll() {
        return tenantDao.findAll();
    }

    public Tenant get(Long id) {
        return tenantDao.findById(id).orElseThrow(() -> new ResourceNotFoundException("College not found"));
    }

    public Tenant create(String name, Long actorUserId) {
        Tenant tenant = Tenant.builder().name(name).status(TenantStatus.PENDING).build();
        Tenant saved = tenantDao.save(tenant);
        auditService.record(actorUserId, saved.getId(), "Tenant", saved.getId(), "CREATE", null, saved);
        return saved;
    }

    /**
     * Fixes a college's name. Worth having because the name is not cosmetic: it is what
     * students pick at registration, what the app greets them with, and the only handle an
     * admin has on a tenant in the console. Onboarding it with a typo used to be permanent
     * — nothing in the product could change it — so the only fix was another college.
     *
     * <p>tenants.name is UNIQUE, so a clash is an ordinary outcome here rather than a bug:
     * an admin tidying up a college onboarded three times is exactly who hits it. Translated
     * into a message the screen can show, not a 500.
     */
    public Tenant rename(Long id, String name, Long actorUserId) {
        Tenant before = get(id);
        try {
            tenantDao.updateName(id, name);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("Another college is already called “" + name + "”.");
        }
        auditService.record(actorUserId, id, "Tenant", id, "RENAME", before.getName(), name);
        return get(id);
    }

    public void setStatus(Long id, TenantStatus status, Long actorUserId) {
        Tenant before = get(id);
        tenantDao.updateStatus(id, status);
        auditService.record(actorUserId, id, "Tenant", id, "STATUS_" + status, before.getStatus(), status);
    }

    /**
     * Permanently removes a college, its canteens, their menus, and its staff accounts.
     *
     * <p>The narrow case this exists for is a college onboarded by mistake — the same case
     * {@link OutletService#delete} covers one level down, and it is refused on the same two
     * grounds, for the same reasons.
     *
     * <p><b>Orders.</b> Refused outright once even one order exists. Orders and payments are
     * financial records with a retention obligation behind them, and there is no version of
     * "delete the college but keep the receipts" that leaves the data honest. Suspending the
     * college does everything an admin actually wants in that case.
     *
     * <p><b>Students.</b> Refused while any student account is attached. Those are real
     * people, and erasing a person's account is what the privacy flow is for
     * ({@code UserService.deleteOwnAccount}), with the anonymisation and the audit trail
     * that go with it. A cleanup button must not become a back door around it.
     *
     * <p>Staff accounts are the exception and do go, because they exist only to sign in to
     * this college; a tenant-scoped account whose tenant is gone is not a coherent row. The
     * confirmation screen says so before anyone presses the button.
     *
     * @return how many staff accounts were removed with it
     */
    @Transactional
    public int delete(Long id, Long actorUserId) {
        Tenant tenant = get(id);

        int orders = tenantDao.countOrders(id);
        if (orders > 0) {
            throw new BusinessException(tenant.getName() + " has " + orders + " order"
                    + (orders == 1 ? "" : "s") + " on record and cannot be deleted — those are financial "
                    + "records tied to it. Suspend it instead: it disappears from the app and stops taking "
                    + "orders, but its history stays intact.");
        }

        int students = tenantDao.countStudents(id);
        if (students > 0) {
            throw new BusinessException(tenant.getName() + " has " + students + " student account"
                    + (students == 1 ? "" : "s") + " attached and cannot be deleted. Those belong to real "
                    + "people — a student removes their own account from Your data, which anonymises it "
                    + "properly. Suspend the college instead.");
        }

        int staff = tenantDao.findStaffUserIds(id).size();
        tenantDao.delete(id);

        // Recorded with a null tenant, because the tenant it would point at no longer
        // exists and the column is a foreign key. The entry still names the college.
        auditService.record(actorUserId, null, "Tenant", id, "DELETE", tenant, null);
        log.warn("Tenant {} ({}) deleted by user {}; {} staff account(s) removed",
                id, tenant.getName(), actorUserId, staff);
        return staff;
    }

    public void uploadLogo(Long id, MultipartFile file, Long actorUserId) {
        get(id); // 404s cleanly if the tenant doesn't exist
        String filename = fileStorageService.storeLogo(id, file);
        tenantDao.updateLogoPath(id, filename);
        auditService.record(actorUserId, id, "Tenant", id, "LOGO_UPLOAD", null, filename);
    }
}
