package com.bitesite.service;

public interface EmailService {

    /** True once real SMTP credentials are supplied (see application.yml spring.mail.*). */
    boolean isConfigured();

    void sendOtpEmail(String toEmail, String recipientName, String code);

    /** A password-reset code. Worded differently from verification on purpose: someone
     * receiving this unexpectedly needs to be told to ignore it, not to enter it. */
    void sendPasswordResetEmail(String toEmail, String recipientName, String code);
}
