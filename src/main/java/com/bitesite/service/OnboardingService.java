package com.bitesite.service;

import com.bitesite.dao.OnboardingDao;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.OnboardingLead;
import com.bitesite.model.OnboardingStage;
import com.bitesite.tenant.Tenant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** The onboarding "sales" pipeline — lead tracking only, owned solely by the super admin
 * (no separate restricted sales role). Converting a lead to a live tenant is a distinct,
 * explicit step, not automatic on reaching the ONBOARDED stage. */
@Service
@RequiredArgsConstructor
public class OnboardingService {

    private final OnboardingDao onboardingDao;
    private final TenantService tenantService;

    public List<OnboardingLead> listAll() {
        return onboardingDao.findAll();
    }

    public OnboardingLead get(Long id) {
        return onboardingDao.findById(id).orElseThrow(() -> new ResourceNotFoundException("Lead not found"));
    }

    public OnboardingLead createLead(String collegeName, String contactName, String contactEmail,
            String contactPhone, String notes) {
        OnboardingLead lead = OnboardingLead.builder()
                .collegeName(collegeName)
                .contactName(contactName)
                .contactEmail(contactEmail)
                .contactPhone(contactPhone)
                .stage(OnboardingStage.LEAD)
                .notes(notes)
                .build();
        return onboardingDao.save(lead);
    }

    public void advanceStage(Long id, OnboardingStage stage) {
        get(id);
        onboardingDao.updateStage(id, stage);
    }

    public Tenant convertToTenant(Long leadId, Long actorUserId) {
        OnboardingLead lead = get(leadId);
        Tenant tenant = tenantService.create(lead.getCollegeName(), actorUserId);
        onboardingDao.linkTenant(leadId, tenant.getId());
        onboardingDao.updateStage(leadId, OnboardingStage.ONBOARDED);
        return tenant;
    }
}
