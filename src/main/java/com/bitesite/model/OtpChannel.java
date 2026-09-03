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
    PWRESET,

    /**
     * Codes proving a new email address during an address change. Its own channel for the
     * same reason PWRESET is: a code issued to prove a <em>new</em> mailbox must not be
     * accepted as proof of the old one, and issuing one must not wipe a pending reset.
     *
     * <p>Fits VARCHAR(10) with one character to spare.
     */
    EMAILSWAP,

    /**
     * Second-factor codes at sign-in for platform accounts. Its own channel so a code
     * issued to get past a login cannot be replayed against a password reset or an address
     * change, and so issuing one does not clear a code the user is midway through using
     * elsewhere. Eight characters — VARCHAR(10) holds it.
     */
    LOGIN2FA
}
