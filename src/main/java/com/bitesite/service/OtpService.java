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
