package com.bitesite.service;

import com.bitesite.dao.TechConfigDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TechConfigServiceTest {

    @Mock private TechConfigDao techConfigDao;
    @Mock private AuditService auditService;

    private TechConfigService techConfigService;

    @BeforeEach
    void setUp() {
        techConfigService = new TechConfigService(techConfigDao, auditService);
    }

    @Test
    void setUpsertsAndAuditsWithTheKeyInTheActionName() {
        techConfigService.set(1L, "feature.ratings.enabled", "false", 100L);

        verify(techConfigDao).upsert(1L, "feature.ratings.enabled", "false");
        verify(auditService).record(100L, 1L, "TechConfig", null, "SET_feature.ratings.enabled", null, "false");
    }
}
