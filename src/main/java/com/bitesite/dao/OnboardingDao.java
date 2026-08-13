package com.bitesite.dao;

import com.bitesite.model.OnboardingLead;
import com.bitesite.model.OnboardingStage;

import java.util.List;
import java.util.Optional;

public interface OnboardingDao {
    OnboardingLead save(OnboardingLead lead);

    Optional<OnboardingLead> findById(Long id);

    List<OnboardingLead> findAll();

    void updateStage(Long id, OnboardingStage stage);

    void linkTenant(Long id, Long tenantId);
}
