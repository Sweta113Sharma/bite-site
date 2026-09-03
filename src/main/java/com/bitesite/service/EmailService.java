package com.bitesite.service;

public interface EmailService {

    /** True once real SMTP credentials are supplied (see application.yml spring.mail.*). */
    boolean isConfigured();

    void sendOtpEmail(String toEmail, String recipientName, String code);

    /** A password-reset code. Worded differently from verification on purpose: someone
     * receiving this unexpectedly needs to be told to ignore it, not to enter it. */
    void sendPasswordResetEmail(String toEmail, String recipientName, String code);

    /** An order-status update: confirmed, ready, cancelled, refunded. Same wording the
     * push notification carries, so a student who has both does not get two differently
     * worded accounts of the same event. */
    void sendOrderUpdateEmail(String toEmail, String recipientName, String subject, String body);

    /** A code sent to a proposed new address, to prove the person asking actually holds
     * it. Goes to the new address, never the old one — sending it to the old one would
     * prove nothing about the new. */
    void sendEmailChangeEmail(String toEmail, String recipientName, String code);

    /** A sign-in code for a platform account. Worded so that receiving one unexpectedly
     * reads as an alarm, because it means someone has the password. */
    void sendLoginCodeEmail(String toEmail, String recipientName, String code);
}
