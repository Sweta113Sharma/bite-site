package com.bitesite.service;

import com.bitesite.dao.UserDao;
import com.bitesite.exception.BusinessException;
import com.bitesite.exception.DuplicateEmailException;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.Role;
import com.bitesite.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserDao userDao;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final EmailService emailService;
    private final SmsService smsService;
    private final PushNotificationService pushNotificationService;

    public User registerStudent(Long tenantId, String name, String rawEmail, String rawPassword,
            String phone, String rollNo) {
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (userDao.existsByEmail(email)) {
            throw new DuplicateEmailException(email);
        }
        boolean hasPhone = phone != null && !phone.isBlank();
        User user = User.builder()
                .tenantId(tenantId)
                .name(name.trim())
                .email(email)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .phone(phone)
                .rollNo(rollNo)
                .role(Role.USER)
                .active(true)
                // Only gated once SMTP/Twilio is actually configured — otherwise there's no
                // way to deliver a code, so self-registration stays unrestricted. Phone is
                // additionally only gated when one was actually supplied.
                .emailVerified(!emailService.isConfigured())
                .phoneVerified(!hasPhone || !smsService.isConfigured())
                .build();
        return userDao.save(user);
    }

    public User createUser(Long tenantId, Long outletId, String name, String rawEmail, String rawPassword,
            Role role) {
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (userDao.existsByEmail(email)) {
            throw new DuplicateEmailException(email);
        }
        User user = User.builder()
                .tenantId(tenantId)
                .outletId(outletId)
                .name(name.trim())
                .email(email)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .role(role)
                .active(true)
                // Admin-provisioned accounts are trusted at creation time, not self-registered.
                .emailVerified(true)
                .phoneVerified(true)
                .build();
        return userDao.save(user);
    }

    public Optional<User> findById(Long userId) {
        return userDao.findById(userId);
    }

    public Optional<User> findByEmail(String email) {
        return userDao.findByEmail(email);
    }

    public List<User> findByTenantId(Long tenantId) {
        return userDao.findByTenantId(tenantId);
    }

    public List<User> findPlatformUsers() {
        return userDao.findPlatformUsers();
    }

    public void setActive(Long userId, boolean active) {
        userDao.setActive(userId, active);
    }

    /** Grant an additional role. {@code user_roles.grantRole} is itself the audit record
     * (via {@code role_audit}) — nothing further to log here. */
    public void grantRole(Long userId, Role role, Long actorUserId) {
        userDao.grantRole(userId, role, actorUserId);
    }

    /** Revoke a role, refusing to leave the account with none — that would lock them out
     * of every portal, not just the one this role belonged to. If the revoked role was
     * their active one, falls back to another role they still hold so their next request
     * doesn't reference a role that's gone. */
    public void revokeRole(Long userId, Role role, Long actorUserId) {
        User user = userDao.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getRoles().size() <= 1) {
            throw new BusinessException("Can't revoke a user's only remaining role.");
        }
        userDao.revokeRole(userId, role, actorUserId);
        if (user.getActiveRole() == role) {
            Role fallback = user.getRoles().stream().filter(r -> r != role).findFirst().orElseThrow();
            userDao.updateActiveRole(userId, fallback);
        }
    }

    /** Staff at one outlet, for that outlet's own manager. */
    public List<User> findByOutlet(Long outletId, Long tenantId) {
        return userDao.findByOutletId(outletId, tenantId);
    }

    /**
     * Switches off a staff account, but only one belonging to the manager's own outlet.
     *
     * <p>The outlet and tenant are checked against the target rather than trusted from the
     * request, so a crafted user id cannot reach across to another canteen — or to a
     * platform account, which has no outlet at all and therefore never matches.
     */
    public void deactivateOutletstaff(Long userId, Long outletId, Long tenantId, Long actorUserId) {
        User target = userDao.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Staff account not found"));
        if (!outletId.equals(target.getOutletId()) || !tenantId.equals(target.getTenantId())) {
            throw new ResourceNotFoundException("Staff account not found");
        }
        userDao.setActive(userId, false);
        auditService.record(actorUserId, tenantId, "User", userId, "DEACTIVATE_STAFF", true, false);
    }

    /**
     * Self-service right-to-erasure: scrubs the account's PII and deactivates it. Order,
     * payment, and grievance history is deliberately left in place — the canteen has a
     * legitimate operational/accounting need to keep those records, and they no longer
     * carry identifying information once this runs.
     */
    public void deleteOwnAccount(Long userId, Long tenantId) {
        String placeholderEmail = "deleted-" + userId + "-" + RANDOM.nextInt(1_000_000) + "@deleted.bitesite.local";
        String unusablePasswordHash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
        userDao.anonymize(userId, placeholderEmail, unusablePasswordHash);
        // Anonymising the users row is not enough on its own: the row survives (order and
        // payment history has to keep pointing somewhere), so every push subscription
        // hanging off it stays valid and the person's phone carries on receiving
        // notifications for an account they were told was deleted.
        pushNotificationService.unsubscribeAll(userId);
        auditService.record(userId, tenantId, "User", userId, "SELF_DELETE_ACCOUNT", null, null);
    }
}
