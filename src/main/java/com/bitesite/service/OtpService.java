package com.bitesite.service;

import com.bitesite.dao.OtpCodeDao;
import com.bitesite.dao.UserDao;
import com.bitesite.model.OtpChannel;
import com.bitesite.model.OtpCode;
import com.bitesite.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

/** Issues and verifies 6-digit OTPs for both email and phone verification. Codes are never
 * stored in plaintext — only a SHA-256 hash — and each issued code allows a limited number
 * of wrong guesses before it's dead (request a fresh one via issueEmailOtp/issuePhoneOtp,
 * which also invalidates whatever code preceded it). */
@Service
@RequiredArgsConstructor
public class OtpService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final int MAX_VERIFY_ATTEMPTS = 5;

    private final OtpCodeDao otpCodeDao;
    private final UserDao userDao;
    private final EmailService emailService;
    private final SmsService smsService;

    /** No-ops when SMTP isn't configured — the account was already created email-verified
     * in that case (see UserService.registerStudent). */
    public void issueEmailOtp(User user) {
        if (!emailService.isConfigured()) {
            return;
        }
        String code = issue(user.getId(), OtpChannel.EMAIL);
        emailService.sendOtpEmail(user.getEmail(), user.getName(), code);
    }

    /** No-ops when SMS isn't configured or the user didn't supply a phone number — the
     * account was already created phone-verified in either case. */
    public void issuePhoneOtp(User user) {
        if (!smsService.isConfigured() || user.getPhone() == null || user.getPhone().isBlank()) {
            return;
        }
        String code = issue(user.getId(), OtpChannel.PHONE);
        smsService.sendOtp(user.getPhone(), code);
    }

    /** Returns true and marks the channel verified if the submitted code matches the most
     * recently issued one for that channel and hasn't expired or been guessed wrong too
     * many times; false otherwise. Does not distinguish "no code was ever issued" from
     * "wrong code" in its return value — same anti-enumeration reasoning as the old
     * link-based flow's resend(). */
    public boolean verify(Long userId, OtpChannel channel, String submittedCode) {
        Optional<OtpCode> found = otpCodeDao.findLatest(userId, channel);
        if (found.isEmpty()) {
            return false;
        }
        OtpCode otp = found.get();
        if (otp.getExpiresAt().isBefore(LocalDateTime.now()) || otp.getAttempts() >= MAX_VERIFY_ATTEMPTS) {
            return false;
        }
        if (!otp.getCodeHash().equals(hash(submittedCode))) {
            otpCodeDao.incrementAttempts(otp.getId());
            return false;
        }
        if (channel == OtpChannel.EMAIL) {
            userDao.markEmailVerified(userId);
        } else {
            userDao.markPhoneVerified(userId);
        }
        otpCodeDao.deleteByUserIdAndChannel(userId, channel);
        return true;
    }

    /**
     * Issues a password-reset code and emails it. Silent when SMTP is unconfigured, for
     * the same reason the verification path is: a missing relay must not throw into a
     * request the caller cannot fix.
     */
    public void issuePasswordResetOtp(User user) {
        if (!emailService.isConfigured()) {
            return;
        }
        String code = issue(user.getId(), OtpChannel.PWRESET);
        emailService.sendPasswordResetEmail(user.getEmail(), user.getName(), code);
    }

    /**
     * Checks a reset code without the verification side effects.
     *
     * <p>{@link #verify} marks the email or phone verified on success, which is right when
     * the user is proving a new address and wrong here — a reset should not silently
     * upgrade an unverified account. Successful codes are consumed either way, so one code
     * cannot be replayed to change the password twice.
     */
    public boolean verifyPasswordReset(Long userId, String submittedCode) {
        Optional<OtpCode> found = otpCodeDao.findLatest(userId, OtpChannel.PWRESET);
        if (found.isEmpty()) {
            return false;
        }
        OtpCode otp = found.get();
        if (otp.getExpiresAt().isBefore(LocalDateTime.now()) || otp.getAttempts() >= MAX_VERIFY_ATTEMPTS) {
            return false;
        }
        if (!otp.getCodeHash().equals(hash(submittedCode))) {
            otpCodeDao.incrementAttempts(otp.getId());
            return false;
        }
        otpCodeDao.deleteByUserIdAndChannel(userId, OtpChannel.PWRESET);
        return true;
    }

    /**
     * Issues a code proving a proposed new address, and sends it <em>there</em> — not to
     * the address currently on the account, which would prove nothing about the new one.
     */
    public void issueEmailChangeOtp(User user, String newEmail) {
        if (!emailService.isConfigured()) {
            return;
        }
        String code = issue(user.getId(), OtpChannel.EMAILSWAP);
        emailService.sendEmailChangeEmail(newEmail, user.getName(), code);
    }

    /**
     * Checks an address-change code. Like {@link #verifyPasswordReset} this deliberately
     * skips {@link #verify}'s side effects: marking the <em>old</em> address verified on
     * the strength of a code sent to a different mailbox would be exactly backwards.
     * Applying the change is the caller's job, and that is what sets email_verified.
     */
    public boolean verifyEmailChange(Long userId, String submittedCode) {
        Optional<OtpCode> found = otpCodeDao.findLatest(userId, OtpChannel.EMAILSWAP);
        if (found.isEmpty()) {
            return false;
        }
        OtpCode otp = found.get();
        if (otp.getExpiresAt().isBefore(LocalDateTime.now()) || otp.getAttempts() >= MAX_VERIFY_ATTEMPTS) {
            return false;
        }
        if (!otp.getCodeHash().equals(hash(submittedCode))) {
            otpCodeDao.incrementAttempts(otp.getId());
            return false;
        }
        otpCodeDao.deleteByUserIdAndChannel(userId, OtpChannel.EMAILSWAP);
        return true;
    }

    /**
     * Issues a sign-in second factor to the address on the account.
     *
     * @return false when no code could be issued at all — no mail relay, or no address to
     *         send to. The caller must then let the sign-in through rather than demand a
     *         code that was never sent: a second factor nobody can receive is not security,
     *         it is a locked door with the key thrown away, and the account it locks out is
     *         the one that would go and fix the relay.
     */
    public boolean issueLoginOtp(User user) {
        if (!emailService.isConfigured() || user.getEmail() == null || user.getEmail().isBlank()) {
            return false;
        }
        String code = issue(user.getId(), OtpChannel.LOGIN2FA);
        emailService.sendLoginCodeEmail(user.getEmail(), user.getName(), code);
        return true;
    }

    /** Checks a sign-in second factor. No verification side effects, and the code is
     * consumed on success so it cannot be replayed into a second session. */
    public boolean verifyLoginOtp(Long userId, String submittedCode) {
        Optional<OtpCode> found = otpCodeDao.findLatest(userId, OtpChannel.LOGIN2FA);
        if (found.isEmpty()) {
            return false;
        }
        OtpCode otp = found.get();
        if (otp.getExpiresAt().isBefore(LocalDateTime.now()) || otp.getAttempts() >= MAX_VERIFY_ATTEMPTS) {
            return false;
        }
        if (!otp.getCodeHash().equals(hash(submittedCode))) {
            otpCodeDao.incrementAttempts(otp.getId());
            return false;
        }
        otpCodeDao.deleteByUserIdAndChannel(userId, OtpChannel.LOGIN2FA);
        return true;
    }

    private String issue(Long userId, OtpChannel channel) {
        String code = generateCode();
        otpCodeDao.deleteByUserIdAndChannel(userId, channel);
        otpCodeDao.save(OtpCode.builder()
                .userId(userId)
                .channel(channel)
                .codeHash(hash(code))
                .expiresAt(LocalDateTime.now().plus(CODE_TTL))
                .build());
        return code;
    }

    private String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private String hash(String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available on the JVM", e);
        }
    }
}
