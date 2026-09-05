package com.bitesite.dao;

import com.bitesite.model.FcmToken;

import java.util.List;

public interface FcmTokenDao {
    /** Upserts on the token — FCM reissues tokens to the same install, and a shared phone
     * can sign in as a second student, so both re-point the existing row at whoever holds
     * the device now rather than leaving a row that notifies the previous owner. */
    void save(FcmToken token);

    List<FcmToken> findByUserId(Long userId);

    /** Unscoped, for the send path only: FCM has just told us the token is dead. */
    void deleteByToken(String token);

    /** Deletes a token only if it belongs to this user. Returns rows removed, so a request
     * handler can tell a genuine sign-out from an attempt on someone else's device. */
    int deleteByTokenForUser(String token, Long userId);

    /** Every token a user holds — used when erasing an account, so a "deleted" account's
     * phone stops receiving notifications. */
    void deleteByUserId(Long userId);
}
