package com.bitesite.service;

public interface EmailService {

    /** True once real SMTP credentials are supplied (see application.yml spring.mail.*). */
    boolean isConfigured();

    void sendOtpEmail(String toEmail, String recipientName, String code);
}
