package com.bitesite.service;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Async;

/**
 * Twilio account SID/auth token are intentionally left blank until real credentials are
 * supplied (see twilio.* in application.yml) — while blank, isConfigured() is false and
 * callers skip sending rather than let an SMS failure break registration, mirroring
 * {@link SmtpEmailService}'s handling of unconfigured SMTP.
 */
@Service
@Slf4j
public class TwilioSmsService implements SmsService {

    private final String accountSid;
    private final String authToken;
    private final String fromNumber;

    public TwilioSmsService(
            @Value("${twilio.account-sid}") String accountSid,
            @Value("${twilio.auth-token}") String authToken,
            @Value("${twilio.from-number}") String fromNumber) {
        this.accountSid = accountSid;
        this.authToken = authToken;
        this.fromNumber = fromNumber;
    }

    @Override
    public boolean isConfigured() {
        return !accountSid.isBlank() && !authToken.isBlank() && !fromNumber.isBlank();
    }

    @Override
    // An HTTP call to Twilio, previously made while the user's browser waited.
    @Async
    public void sendOtp(String toPhone, String code) {
        if (!isConfigured()) {
            log.warn("Twilio not configured — skipping OTP SMS to {}", toPhone);
            return;
        }
        try {
            Twilio.init(accountSid, authToken);
            Message.creator(
                    new PhoneNumber(normalizeIndianNumber(toPhone)),
                    new PhoneNumber(fromNumber),
                    "Your BiteSite verification code is " + code + ". It expires in 10 minutes.")
                    .create();
        } catch (Exception e) {
            log.error("Failed to send OTP SMS to {}", toPhone, e);
        }
    }

    // Indian mobile numbers only for now — the rest of this app is India-specific (₹
    // pricing, DPDP Act references in the privacy policy). StudentRegistrationForm already
    // validates the input as a bare 10-digit number before it reaches here.
    private String normalizeIndianNumber(String phone) {
        return "+91" + phone;
    }
}
