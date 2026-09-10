package com.bitesite.service;

import com.bitesite.exception.BusinessException;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.tenant.Tenant;
import com.bitesite.tenant.TenantCache;
import com.bitesite.tenant.TenantDao;
import com.bitesite.tenant.TenantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

    @Mock private TenantDao tenantDao;
    @Mock private FileStorageService fileStorageService;
    @Mock private AuditService auditService;

    private TenantService tenantService;

    private static final Long ACTOR_ID = 100L;

    @BeforeEach
    void setUp() {
        tenantService = new TenantService(tenantDao, fileStorageService, auditService,
                new TenantCache(tenantDao));
    }

    @Test
    void createStartsInPendingStatusNotActive() {
        when(tenantDao.save(any(Tenant.class))).thenAnswer(inv -> {
            Tenant t = inv.getArgument(0);
            t.setId(5L);
            return t;
        });

        Tenant created = tenantService.create("NID Institute", ACTOR_ID);

        assertThat(created.getStatus()).isEqualTo(TenantStatus.PENDING);
        verify(auditService).record(eq(ACTOR_ID), eq(5L), eq("Tenant"), eq(5L), eq("CREATE"), isNull(), eq(created));
    }

    @Test
    void getThrowsWhenTenantDoesNotExist() {
        when(tenantDao.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tenantService.get(99L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void setStatusAuditsTheTransition() {
        Tenant existing = Tenant.builder().id(5L).name("NID").status(TenantStatus.PENDING).build();
        when(tenantDao.findById(5L)).thenReturn(Optional.of(existing));

        tenantService.setStatus(5L, TenantStatus.ACTIVE, ACTOR_ID);

        verify(tenantDao).updateStatus(5L, TenantStatus.ACTIVE);
        verify(auditService).record(ACTOR_ID, 5L, "Tenant", 5L, "STATUS_ACTIVE", TenantStatus.PENDING, TenantStatus.ACTIVE);
    }

    @Test
    void renameWritesTheNewNameAndAuditsTheOldOne() {
        Tenant before = Tenant.builder().id(5L).name("HTBU").status(TenantStatus.ACTIVE).build();
        Tenant after = Tenant.builder().id(5L).name("HBTU Kanpur").status(TenantStatus.ACTIVE).build();
        when(tenantDao.findById(5L)).thenReturn(Optional.of(before), Optional.of(after));

        Tenant result = tenantService.rename(5L, "HBTU Kanpur", ACTOR_ID);

        assertThat(result.getName()).isEqualTo("HBTU Kanpur");
        verify(tenantDao).updateName(5L, "HBTU Kanpur");
        verify(auditService).record(ACTOR_ID, 5L, "Tenant", 5L, "RENAME", "HTBU", "HBTU Kanpur");
    }

    /** tenants.name is UNIQUE, and colliding with an existing college is a thing an admin
     *  tidying up duplicates does routinely — it has to read as a message, not a 500. */
    @Test
    void renameToANameAlreadyTakenIsAShowableErrorNotACrash() {
        Tenant before = Tenant.builder().id(5L).name("HTBU").status(TenantStatus.ACTIVE).build();
        when(tenantDao.findById(5L)).thenReturn(Optional.of(before));
        doThrow(new DuplicateKeyException("uq_tenants_name")).when(tenantDao).updateName(5L, "HBTU Kanpur");

        assertThatThrownBy(() -> tenantService.rename(5L, "HBTU Kanpur", ACTOR_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("HBTU Kanpur");
        verifyNoInteractions(auditService);
    }

    @Test
    void renameOfAMissingTenant404sBeforeWritingAnything() {
        when(tenantDao.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tenantService.rename(99L, "Anything", ACTOR_ID))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(tenantDao, never()).updateName(anyLong(), anyString());
    }

    @Test
    void deleteRemovesTheCollegeAndAuditsItWithANullTenant() {
        Tenant existing = Tenant.builder().id(5L).name("HBTU").status(TenantStatus.PENDING).build();
        when(tenantDao.findById(5L)).thenReturn(Optional.of(existing));
        when(tenantDao.countOrders(5L)).thenReturn(0);
        when(tenantDao.countStudents(5L)).thenReturn(0);
        when(tenantDao.findStaffUserIds(5L)).thenReturn(List.of(11L, 12L));

        int staff = tenantService.delete(5L, ACTOR_ID);

        assertThat(staff).isEqualTo(2);
        verify(tenantDao).delete(5L);
        // Null tenant, because audit_log.tenant_id is a foreign key at the row we just removed.
        verify(auditService).record(ACTOR_ID, null, "Tenant", 5L, "DELETE", existing, null);
    }

    /** Orders are financial records; the college that owns them must survive them. */
    @Test
    void deleteIsRefusedOnceAnyOrderExists() {
        Tenant existing = Tenant.builder().id(5L).name("HBTU").status(TenantStatus.ACTIVE).build();
        when(tenantDao.findById(5L)).thenReturn(Optional.of(existing));
        when(tenantDao.countOrders(5L)).thenReturn(3);

        assertThatThrownBy(() -> tenantService.delete(5L, ACTOR_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("3 orders");
        verify(tenantDao, never()).delete(anyLong());
        verifyNoInteractions(auditService);
    }

    /** A cleanup button must not become a back door around the privacy flow. */
    @Test
    void deleteIsRefusedWhileAnyStudentAccountIsAttached() {
        Tenant existing = Tenant.builder().id(5L).name("HBTU").status(TenantStatus.ACTIVE).build();
        when(tenantDao.findById(5L)).thenReturn(Optional.of(existing));
        when(tenantDao.countOrders(5L)).thenReturn(0);
        when(tenantDao.countStudents(5L)).thenReturn(1);

        assertThatThrownBy(() -> tenantService.delete(5L, ACTOR_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("1 student account");
        verify(tenantDao, never()).delete(anyLong());
        verifyNoInteractions(auditService);
    }

    @Test
    void deleteChecksOrdersBeforeStudentsSoTheFirstMessageIsTheHarderStop() {
        Tenant existing = Tenant.builder().id(5L).name("HBTU").status(TenantStatus.ACTIVE).build();
        when(tenantDao.findById(5L)).thenReturn(Optional.of(existing));
        when(tenantDao.countOrders(5L)).thenReturn(2);

        assertThatThrownBy(() -> tenantService.delete(5L, ACTOR_ID))
                .hasMessageContaining("order");
        verify(tenantDao, never()).countStudents(anyLong());
    }

    @Test
    void deleteOfAMissingTenant404sBeforeCountingAnything() {
        when(tenantDao.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tenantService.delete(99L, ACTOR_ID))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(tenantDao, never()).delete(anyLong());
    }

    @Test
    void uploadLogo404sCleanlyForAMissingTenantBeforeTouchingStorage() {
        when(tenantDao.findById(99L)).thenReturn(Optional.empty());
        MockMultipartFile file = new MockMultipartFile("logo", "logo.png", "image/png", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> tenantService.uploadLogo(99L, file, ACTOR_ID))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(fileStorageService);
    }

    @Test
    void uploadLogoStoresTheFileThenPersistsTheReturnedPath() {
        Tenant existing = Tenant.builder().id(5L).name("NID").status(TenantStatus.PENDING).build();
        when(tenantDao.findById(5L)).thenReturn(Optional.of(existing));
        MockMultipartFile file = new MockMultipartFile("logo", "logo.png", "image/png", new byte[]{1, 2, 3});
        when(fileStorageService.storeLogo(5L, file)).thenReturn("/uploads/logos/tenant-5-abc.png");

        tenantService.uploadLogo(5L, file, ACTOR_ID);

        verify(tenantDao).updateLogoPath(5L, "/uploads/logos/tenant-5-abc.png");
        verify(auditService).record(eq(ACTOR_ID), eq(5L), eq("Tenant"), eq(5L), eq("LOGO_UPLOAD"), isNull(),
                eq("/uploads/logos/tenant-5-abc.png"));
    }
}
