package com.bitesite.service;

import com.bitesite.config.RateLimiter;
import com.bitesite.dao.UserDao;
import com.bitesite.exception.BusinessException;
import com.bitesite.exception.DuplicateEmailException;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.Role;
import com.bitesite.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.List;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Verification emails per day, against a plan of 300. The gap leaves room for
     * password resets and admin sign-in codes on the same day as a large intake. */
    private static final int DAILY_REGISTRATION_EMAIL_BUDGET = 200;

    private final UserDao userDao;
    private final RateLimiter rateLimiter;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final EmailService emailService;
    private final OtpService otpService;
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
                .emailVerified(!canSendVerificationEmail())
                .phoneVerified(!hasPhone || !smsService.isConfigured())
                .build();
        return userDao.save(user);
    }

    /**
     * Whether a verification code can actually be delivered right now.
     *
     * <p>The mail plan is 300 messages a day, shared with password resets and admin
     * sign-in codes. Registration is one message per new student and cannot be rationed
     * the way notifications can — refusing to send would refuse the signup. So on the day
     * an intake exhausts the allowance, accounts are created already verified rather than
     * created unusable: the same thing this app already does when no relay is configured
     * at all. A student who cannot receive a code they were never sent must not be left
     * holding an account they cannot sign in to.
     *
     * <p>Consuming the budget here rather than at send time is deliberate — the caller
     * decides whether to gate the account on the answer, so the count has to reflect the
     * decision, not the attempt.
     */
    private boolean canSendVerificationEmail() {
        if (!emailService.isConfigured()) {
            return false;
        }
        boolean withinBudget = rateLimiter.tryConsume(
                "registration-email-daily", DAILY_REGISTRATION_EMAIL_BUDGET, Duration.ofDays(1));
        if (!withinBudget) {
            log.warn("Daily registration-email budget of {} reached — creating accounts pre-verified "
                    + "so signup keeps working. Raise the mail plan if this repeats.",
                    DAILY_REGISTRATION_EMAIL_BUDGET);
        }
        return withinBudget;
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

    // ---------- Profile ----------

    /**
     * Self-service profile edit: the fields a person can legitimately change about
     * themselves. Email is not among them — it is the login identifier and a uniqueness
     * constraint, so changing it is a different job with its own verification.
     *
     * <p>The subtle part is {@code phone_verified}. A phone number that changes has not
     * been proved, and re-proving it is only possible when SMS is actually configured, so
     * the flag follows the same rule {@link #registerStudent} uses: verified unless there
     * is both a number to check and a way to check it. It is left untouched when the
     * number did not change, which matters — recomputing it would quietly mark a
     * half-finished signup's unverified number as verified.
     *
     * @return true when the new number still has to be verified, so the caller can send
     *         the user to the OTP screen instead of leaving them locked out at next login
     */
    public boolean updateOwnProfile(Long userId, String name, String phone, String rollNo) {
        User user = userDao.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        String newPhone = blankToNull(phone);
        String newRollNo = blankToNull(rollNo);
        boolean phoneChanged = !Objects.equals(newPhone, user.getPhone());
        boolean needsVerification = phoneChanged && newPhone != null && smsService.isConfigured();
        boolean phoneVerified = phoneChanged ? !needsVerification : user.isPhoneVerified();

        userDao.updateProfile(userId, name.trim(), newPhone, newRollNo, phoneVerified);
        auditService.record(userId, user.getTenantId(), "User", userId, "UPDATE_PROFILE", null, null);
        return needsVerification;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // ---------- Email address ----------

    /**
     * Stages a new sign-in address and sends a code to it. Nothing changes on the account
     * until {@link #confirmEmailChange} succeeds — the old address still signs in, and a
     * request that is never confirmed simply expires unused.
     *
     * <p>The password is re-checked for the same reason a password change re-checks it:
     * whoever takes over this address takes over the account, so a left-open session must
     * not be enough on its own.
     */
    public void requestEmailChange(Long userId, String rawNewEmail, String currentPassword) {
        User user = userDao.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException("That isn't your current password.");
        }
        String newEmail = rawNewEmail.trim().toLowerCase(Locale.ROOT);
        if (newEmail.equals(user.getEmail())) {
            throw new BusinessException("That is already your email address.");
        }
        // Checked here for a useful error, and again at the swap — between the two a
        // different account could claim it, and users.email is what actually decides.
        if (userDao.existsByEmail(newEmail)) {
            throw new BusinessException("That email address is already in use.");
        }
        if (!emailService.isConfigured()) {
            throw new BusinessException("Email isn't configured yet, so a new address can't be confirmed.");
        }
        userDao.setPendingEmail(userId, newEmail);
        otpService.issueEmailChangeOtp(user, newEmail);
        auditService.record(userId, user.getTenantId(), "User", userId, "REQUEST_EMAIL_CHANGE", null, null);
    }

    /**
     * Completes a staged change once the code sent to the new address has been verified.
     *
     * <p>Re-checks uniqueness immediately before the swap. The check at request time is
     * for a good error message; this one is the one that matters, because two people can
     * stage the same address and only the first to prove it should get it.
     */
    public void confirmEmailChange(Long userId) {
        User user = userDao.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        String pending = user.getPendingEmail();
        if (pending == null || pending.isBlank()) {
            throw new BusinessException("There is no email change waiting to be confirmed.");
        }
        if (userDao.existsByEmail(pending)) {
            userDao.setPendingEmail(userId, null);
            throw new BusinessException("That email address was taken while you were confirming it.");
        }
        userDao.applyPendingEmail(userId, pending);
        // The old address is worth recording: it is the only trace of who the account used
        // to be reachable as, and support will be asked about it.
        auditService.record(userId, user.getTenantId(), "User", userId, "CHANGE_EMAIL",
                user.getEmail(), pending);
    }

    /** Abandons a staged change. */
    public void cancelEmailChange(Long userId) {
        userDao.setPendingEmail(userId, null);
    }

    // ---------- Passwords ----------

    /**
     * Self-service password change for a signed-in user.
     *
     * <p>The current password is re-checked here rather than trusted from the session.
     * A live session is not proof of who is at the keyboard — a phone left on a canteen
     * counter or a library machine left signed in is enough — so knowing the existing
     * password is what separates the account holder from whoever sat down next.
     */
    public void changeOwnPassword(Long userId, String currentPassword, String newPassword) {
        User user = userDao.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException("That isn't your current password.");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessException("Your new password must be different from your current one.");
        }
        userDao.updatePasswordHash(userId, passwordEncoder.encode(newPassword));
        // Neither password goes into the audit row — before/after stay null. The record is
        // that a change happened and when, which is what an investigation needs.
        auditService.record(userId, user.getTenantId(), "User", userId, "CHANGE_PASSWORD", null, null);
    }

    /**
     * Sets a new password after a reset code has been verified. Deliberately takes no old
     * password: the caller must already have consumed a single-use {@code PWRESET} code
     * via {@link OtpService#verifyPasswordReset}, which is what establishes that whoever
     * is asking holds the account's mailbox.
     *
     * <p>Does not touch {@code email_verified}. Proving mailbox control here would be
     * enough to justify flipping it, but a reset should not quietly complete a
     * verification the user never finished — they finish that on {@code /verify}, and the
     * login page already points them there.
     */
    public void resetPassword(Long userId, String newPassword) {
        User user = userDao.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        userDao.updatePasswordHash(userId, passwordEncoder.encode(newPassword));
        auditService.record(userId, user.getTenantId(), "User", userId, "RESET_PASSWORD", null, null);
    }

    /**
     * Manager-initiated reset for one of their own outlet's staff. Sends a code to the
     * staff member's own inbox rather than setting a password the manager then has to read
     * out loud — the manager never learns the new credential.
     *
     * <p>Outlet and tenant are checked against the target, not trusted from the request,
     * for the same reason {@link #deactivateOutletstaff} checks them: a crafted id must
     * not reach another canteen's account, or a platform account (which has no outlet and
     * so never matches).
     */
    public void sendStaffPasswordReset(Long userId, Long outletId, Long tenantId, Long actorUserId) {
        User target = userDao.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Staff account not found"));
        if (!outletId.equals(target.getOutletId()) || !tenantId.equals(target.getTenantId())) {
            throw new ResourceNotFoundException("Staff account not found");
        }
        issuePasswordReset(target, actorUserId);
    }

    /** Super-admin-initiated reset for a platform account. Platform accounts are the ones
     * with no tenant, so the null check is also what stops this endpoint being pointed at
     * a student or an outlet account by id. */
    public void sendPlatformUserPasswordReset(Long userId, Long actorUserId) {
        User target = userDao.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (target.getTenantId() != null) {
            throw new ResourceNotFoundException("User not found");
        }
        issuePasswordReset(target, actorUserId);
    }

    /**
     * Super-admin-initiated reset for an outlet account, from the college screen.
     *
     * <p>Distinct from {@link #sendStaffPasswordReset}, which scopes to a single outlet
     * because a canteen manager may only reach their own. A super admin works a college at
     * a time, so the scope is the tenant — but still a scope: the target has to belong to
     * this college and hold an outlet role, so a platform account cannot be reset through
     * a tenant-scoped screen.
     */
    public void sendTenantStaffPasswordReset(Long userId, Long tenantId, Long actorUserId) {
        User target = userDao.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Staff account not found"));
        if (!tenantId.equals(target.getTenantId()) || !target.getRole().isOutletPortalRole()) {
            throw new ResourceNotFoundException("Staff account not found");
        }
        issuePasswordReset(target, actorUserId);
    }

    private void issuePasswordReset(User target, Long actorUserId) {
        // OtpService silently no-ops without SMTP, which is right for a background send but
        // wrong here: an admin who clicks this needs to know the mail never left, not to
        // sit waiting for a code that isn't coming.
        if (!emailService.isConfigured()) {
            throw new BusinessException("Email isn't configured yet, so a reset code can't be delivered.");
        }
        otpService.issuePasswordResetOtp(target);
        auditService.record(actorUserId, target.getTenantId(), "User", target.getId(),
                "SEND_PASSWORD_RESET", null, null);
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
