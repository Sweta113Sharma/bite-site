package com.bitesite.service;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.dao.UserDao;
import com.bitesite.model.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

/**
 * A single global login: every account authenticates with the same email/password,
 * regardless of role or college. Which college's data a user sees afterward is decided
 * by {@code user.tenantId} alone (see {@link com.bitesite.config.TenantResolutionInterceptor})
 * — never by anything the client supplied at login time.
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserDao userDao;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userDao.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("No account for " + email));
        return new AppUserPrincipal(user);
    }
}
