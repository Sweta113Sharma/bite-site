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
    @Async
    @Override
    public void sendEmailChangeEmail(String toEmail, String recipientName, String code) {
        if (!isConfigured()) {
            log.warn("SMTP not configured — skipping email change code to {}", toEmail);
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject("Confirm your new BiteSite email address");
            // This can land in the inbox of someone whose address was typed by mistake, so
            // it has to say what it is for and make ignoring it the safe option.
            helper.setText("""
                    Hi %s,

                    Someone asked to move a BiteSite account to this email address. To confirm
                    it, enter this code in the app:

                    %s

                    The code expires in 10 minutes. Until it is entered, nothing changes and
                    the account keeps its current address.

                    If you were not expecting this, ignore this email — no account of yours
                    has been touched, and whoever typed this address cannot use it without
                    the code above.
                    """.formatted(recipientName, code));
            mailSender.send(message);
        } catch (Exception e) {
            log.error("Failed to send email change code to {}", toEmail, e);
        }
    }

    @Async
    @Override
    public void sendLoginCodeEmail(String toEmail, String recipientName, String code) {
        if (!isConfigured()) {
            log.warn("SMTP not configured — skipping sign-in code to {}", toEmail);
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject("Your BiteSite sign-in code");
            helper.setText("""
                    Hi %s,

                    Your sign-in code is:

                    %s

                    It expires in 10 minutes.

                    If you are not signing in right now, someone else has your password.
                    Change it as soon as you can — they cannot get in without this code,
                    but they will keep trying.
                    """.formatted(recipientName, code));
            mailSender.send(message);
        } catch (Exception e) {
            log.error("Failed to send sign-in code to {}", toEmail, e);
        }
    }
}
