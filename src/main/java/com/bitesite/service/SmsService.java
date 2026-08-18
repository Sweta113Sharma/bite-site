package com.bitesite.service;

public interface SmsService {

    /** True once real Twilio credentials are supplied (see application.yml twilio.*). */
    boolean isConfigured();

    void sendOtp(String toPhone, String code);
}
