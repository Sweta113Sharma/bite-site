package com.bitesite.service;

import com.bitesite.dao.UserDao;
import com.bitesite.exception.DuplicateEmailException;
import com.bitesite.model.Role;
import com.bitesite.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserDao userDao;
    private final PasswordEncoder passwordEncoder;

    public User registerStudent(Long tenantId, String name, String rawEmail, String rawPassword,
            String phone, String rollNo) {
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (userDao.existsByEmail(email)) {
            throw new DuplicateEmailException(email);
        }
        User user = User.builder()
                .tenantId(tenantId)
                .name(name.trim())
                .email(email)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .phone(phone)
                .rollNo(rollNo)
                .role(Role.STUDENT)
                .active(true)
                .build();
        return userDao.save(user);
    }

    public User createUser(Long tenantId, Long outletId, String name, String rawEmail, String rawPassword,
            Role role) {
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (userDao.existsByEmail(email)) {
            throw new DuplicateEmailException(email);
        }
        User user = User.builder()
                .tenantId(tenantId)
                .outletId(outletId)
                .name(name.trim())
                .email(email)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .role(role)
                .active(true)
                .build();
        return userDao.save(user);
    }

    public List<User> findByTenantId(Long tenantId) {
        return userDao.findByTenantId(tenantId);
    }

    public List<User> findPlatformUsers() {
        return userDao.findPlatformUsers();
    }

    public void setActive(Long userId, boolean active) {
        userDao.setActive(userId, active);
    }
}
