package com.bitesite.dao;

import com.bitesite.model.User;

import java.util.List;
import java.util.Optional;

public interface UserDao {
    Optional<User> findById(Long id);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    List<User> findByTenantId(Long tenantId);

    List<User> findPlatformUsers();

    User save(User user);

    void setActive(Long id, boolean active);
}
