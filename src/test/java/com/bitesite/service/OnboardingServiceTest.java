package com.bitesite.service;

import com.bitesite.dao.OnboardingDao;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.OnboardingLead;
import com.bitesite.model.OnboardingStage;
import com.bitesite.tenant.Tenant;
import com.bitesite.tenant.TenantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OnboardingServiceTest {

    @Mock private OnboardingDao onboardingDao;
    @Mock private TenantService tenantService;

    private OnboardingService onboardingService;

    private static final Long ACTOR_ID = 100L;

    @BeforeEach
    void setUp() {
        onboardingService = new OnboardingService(onboardingDao, tenantService);
    }

    @Test
    void createLeadStartsAtLeadStage() {
        when(onboardingDao.save(any())).thenAnswer(inv -> {
            OnboardingLead lead = inv.getArgument(0);
            lead.setId(1L);
            return lead;
        });

        OnboardingLead lead = onboardingService.createLead("Sharda University", "Dr. Priya",
                "priya@sharda.example", "9812345670", "Interested after demo");

        assertThat(lead.getStage()).isEqualTo(OnboardingStage.LEAD);
        assertThat(lead.getCollegeName()).isEqualTo("Sharda University");
    }

    @Test
    void advanceStageRequiresTheLeadToExist() {
        when(onboardingDao.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> onboardingService.advanceStage(99L, OnboardingStage.DEMO))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(onboardingDao, never()).updateStage(any(), any());
    }

    @Test
    void convertToTenantCreatesATenantLinksItBackAndMarksOnboarded() {
        OnboardingLead lead = OnboardingLead.builder().id(1L).collegeName("Sharda University")
                .stage(OnboardingStage.DEMO).build();
        when(onboardingDao.findById(1L)).thenReturn(Optional.of(lead));
        Tenant newTenant = Tenant.builder().id(4L).name("Sharda University").status(TenantStatus.PENDING).build();
        when(tenantService.create("Sharda University", ACTOR_ID)).thenReturn(newTenant);

        Tenant result = onboardingService.convertToTenant(1L, ACTOR_ID);

        assertThat(result.getId()).isEqualTo(4L);
        verify(onboardingDao).linkTenant(1L, 4L);
        verify(onboardingDao).updateStage(1L, OnboardingStage.ONBOARDED);
    }
}
