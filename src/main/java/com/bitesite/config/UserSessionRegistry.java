package com.bitesite.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Ends a user's server-side sessions by email.
 *
 * <p>Exists because changing a password otherwise does nothing to whoever is already
 * signed in with the old one. Sessions here are DB-backed (Spring Session JDBC) and last
 * 30 minutes of inactivity, so "I changed my password" and "the person who had my
 * password is logged out" were two different things — on a shared campus machine, or
 * after a credential leak, that gap is the whole point of changing it.
 *
 * <p>Spring Session indexes rows by principal name when the session carries a Spring
 * Security context, which is what makes the lookup possible without keeping a registry
 * of our own. The principal name is the login email — see
 * {@link AppUserPrincipal#getUsername()}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserSessionRegistry {

    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    /**
     * Ends every session for this email except {@code keepSessionId}.
     *
     * <p>Used after a signed-in password change: the person doing it should stay where
     * they are, and everyone else holding a session on that account should not.
     */
    public void revokeOtherSessions(String email, String keepSessionId) {
        revoke(email, keepSessionId);
    }

    /** Ends every session for this email. Used after a reset, where the person setting the
     * new password isn't signed in yet and any live session belongs to someone using the
     * password that was just replaced. */
    public void revokeAllSessions(String email) {
        revoke(email, null);
    }

    private void revoke(String email, String keepSessionId) {
        Map<String, ? extends Session> sessions = sessionRepository.findByPrincipalName(email);
        int revoked = 0;
        for (String sessionId : sessions.keySet()) {
            if (sessionId.equals(keepSessionId)) {
                continue;
            }
            sessionRepository.deleteById(sessionId);
            revoked++;
        }
        if (revoked > 0) {
            log.info("Revoked {} session(s) for {}", revoked, email);
        }
    }
}
