package com.bitesite.service;

import com.bitesite.dao.AuditLogDao;
import com.bitesite.model.AuditLogEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock private AuditLogDao auditLogDao;

    private AuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new AuditService(auditLogDao, new ObjectMapper());
    }

    @Test
    void recordSerializesBeforeAndAfterAsJson() {
        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);

        auditService.record(1L, 2L, "MenuItem", 5L, "UPDATE",
                java.util.Map.of("price", "30.00"), java.util.Map.of("price", "27.00"));

        verify(auditLogDao).save(captor.capture());
        AuditLogEntry entry = captor.getValue();
        assertThat(entry.getActorUserId()).isEqualTo(1L);
        assertThat(entry.getTenantId()).isEqualTo(2L);
        assertThat(entry.getBeforeJson()).contains("30.00");
        assertThat(entry.getAfterJson()).contains("27.00");
    }

    @Test
    void recordHandlesNullBeforeAndAfterWithoutError() {
        auditService.record(1L, 2L, "Tenant", 5L, "CREATE", null, null);

        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogDao).save(captor.capture());
        assertThat(captor.getValue().getBeforeJson()).isNull();
        assertThat(captor.getValue().getAfterJson()).isNull();
    }
}
