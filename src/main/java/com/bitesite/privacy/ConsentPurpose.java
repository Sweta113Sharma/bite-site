package com.bitesite.privacy;

/**
 * What a consent record is about. Kept separate rather than one blanket flag: withdrawing
 * agreement to optional notifications is a different act from withdrawing the terms you
 * need in order to hold an account at all.
 */
public enum ConsentPurpose {
    /** Required to hold an account. Withdrawing it means deleting the account. */
    TERMS,
    /** Optional. Order-ready and cancellation alerts. */
    ORDER_NOTIFICATIONS,
    /** Optional, and off unless explicitly given — silence is not consent. */
    MARKETING
}
