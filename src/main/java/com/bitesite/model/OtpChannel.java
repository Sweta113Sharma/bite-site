package com.bitesite.model;

public enum OtpChannel {
    EMAIL,
    PHONE,
    /**
     * Password-reset codes. A separate channel so a reset code cannot be spent as an
     * email-verification code or vice versa, and so issuing one does not wipe a pending
     * verification code — {@code issue()} clears prior codes per channel.
     *
     * <p>Named PWRESET rather than PASSWORD_RESET because {@code otp_codes.channel} is
     * VARCHAR(10); the longer name would be truncated on insert.
     */
    PWRESET
}
