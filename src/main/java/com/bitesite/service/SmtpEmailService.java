package com.bitesite.service;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Async;

/**
 * SMTP host is intentionally left blank until real credentials are supplied (see
 * spring.mail.* in application.yml) — while blank, isConfigured() is false and callers
 * skip sending rather than let a mail failure break registration.
 */
@Service
@Slf4j
public class SmtpEmailService implements EmailService {

    private final JavaMailSender mailSender;
    private final String smtpHost;
    private final String fromAddress;

    public SmtpEmailService(JavaMailSender mailSender,
            @Value("${spring.mail.host}") String smtpHost,
            @Value("${app.mail.from}") String fromAddress) {
        this.mailSender = mailSender;
        this.smtpHost = smtpHost;
        this.fromAddress = fromAddress;
    }

    @Override
    public boolean isConfigured() {
        return smtpHost != null && !smtpHost.isBlank();
    }

    @Override
    // Off the request thread: the SMTP handshake alone can take seconds, and a
    // student registering has no reason to wait for it. Failures are logged below,
    // never surfaced — the account is created either way, and the resend flow exists
    // for the case where the mail genuinely did not arrive.
    @Async
    public void sendOtpEmail(String toEmail, String recipientName, String code) {
        if (!isConfigured()) {
            log.warn("SMTP not configured — skipping verification email to {}", toEmail);
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject("Your BiteSite verification code");
            helper.setText("""
                    Hi %s,

                    Your BiteSite verification code is:

                    %s

                    This code expires in 10 minutes. If you didn't create a BiteSite account, ignore this email.
                    """.formatted(recipientName, code));
            mailSender.send(message);
        } catch (Exception e) {
            log.error("Failed to send verification email to {}", toEmail, e);
        }
    }

    @Override
    @Async
    public void sendPasswordResetEmail(String toEmail, String recipientName, String code) {
        if (!isConfigured()) {
            log.warn("SMTP not configured — skipping password reset email to {}", toEmail);
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject("Reset your BiteSite password");
            // The last line matters: this mail can reach someone who did not ask for it,
            // either by mistyped address or by someone probing their account. It has to
            // tell them nothing has changed yet and that ignoring it is the right move.
            helper.setText("""
                    Hi %s,

                    Your BiteSite password reset code is:

                    %s

                    Enter it within 10 minutes to choose a new password.

                    If you didn't ask to reset your password, ignore this email — nothing
                    has changed and your current password still works.
                    """.formatted(recipientName, code));
            mailSender.send(message);
        } catch (Exception e) {
            log.error("Failed to send password reset email to {}", toEmail, e);
        }
    }
}
