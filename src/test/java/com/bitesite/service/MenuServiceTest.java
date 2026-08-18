package com.bitesite.service;

import com.bitesite.dao.MenuItemDao;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.MenuItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MenuServiceTest {

    @Mock private MenuItemDao menuItemDao;
    @Mock private AuditService auditService;
    @Mock private FileStorageService fileStorageService;

    private MenuService menuService;

    private static final Long TENANT_ID = 1L;
    private static final Long OUTLET_ID = 10L;
    private static final Long ACTOR_ID = 100L;

    @BeforeEach
    void setUp() {
        menuService = new MenuService(menuItemDao, auditService, fileStorageService);
    }

    @Test
    void getThrowsResourceNotFoundWhenMissingOrWrongTenant() {
        when(menuItemDao.findByIdAndTenantId(5L, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> menuService.get(5L, TENANT_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createSavesItemAvailableByDefaultAndAudits() {
        when(menuItemDao.save(any(MenuItem.class))).thenAnswer(inv -> {
            MenuItem item = inv.getArgument(0);
            item.setId(42L);
            return item;
        });

        MenuItem saved = menuService.create(TENANT_ID, OUTLET_ID, "Samosa", "Snacks",
                new BigDecimal("30.00"), null, null, null, ACTOR_ID);

        assertThat(saved.isAvailable()).isTrue();
        assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(saved.getPhotoPath()).isNull();
        verify(auditService).record(eq(ACTOR_ID), eq(TENANT_ID), eq("MenuItem"), eq(42L), eq("CREATE"), isNull(), eq(saved));
        verifyNoInteractions(fileStorageService);
    }

    @Test
    void createUploadsAndStoresThePhotoPathWhenAPhotoIsProvided() {
        when(menuItemDao.save(any(MenuItem.class))).thenAnswer(inv -> inv.getArgument(0));
        MockMultipartFile photo = new MockMultipartFile("photo", "samosa.png", "image/png", new byte[]{1, 2, 3});
        when(fileStorageService.storeMenuItemPhoto(TENANT_ID, photo)).thenReturn("/uploads/menu-photos/samosa.png");

        MenuItem saved = menuService.create(TENANT_ID, OUTLET_ID, "Samosa", "Snacks",
                new BigDecimal("30.00"), null, null, photo, ACTOR_ID);

        assertThat(saved.getPhotoPath()).isEqualTo("/uploads/menu-photos/samosa.png");
    }

    @Test
    void updatePreservesOutletAndAvailabilityFromTheExistingRecord() {
        MenuItem existing = MenuItem.builder().id(5L).tenantId(TENANT_ID).outletId(OUTLET_ID)
                .name("Old Name").category("Snacks").price(new BigDecimal("20.00")).available(false).build();
        when(menuItemDao.findByIdAndTenantId(5L, TENANT_ID)).thenReturn(Optional.of(existing));

        ArgumentCaptor<MenuItem> captor = ArgumentCaptor.forClass(MenuItem.class);
        when(menuItemDao.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        menuService.update(5L, TENANT_ID, "New Name", "Meals", new BigDecimal("50.00"), null, null, null, ACTOR_ID);

        MenuItem updated = captor.getValue();
        assertThat(updated.getOutletId()).isEqualTo(OUTLET_ID); // carried over, not client-suppliable
        assertThat(updated.isAvailable()).isFalse(); // carried over from existing, not reset to true
        assertThat(updated.getName()).isEqualTo("New Name");
        assertThat(updated.getPrice()).isEqualByComparingTo("50.00");
    }

    @Test
    void updateKeepsTheExistingPhotoWhenNoNewPhotoIsProvided() {
        MenuItem existing = MenuItem.builder().id(5L).tenantId(TENANT_ID).outletId(OUTLET_ID)
                .name("Old Name").category("Snacks").photoPath("/uploads/menu-photos/old.png")
                .price(new BigDecimal("20.00")).available(true).build();
        when(menuItemDao.findByIdAndTenantId(5L, TENANT_ID)).thenReturn(Optional.of(existing));
        when(menuItemDao.save(any(MenuItem.class))).thenAnswer(inv -> inv.getArgument(0));

        MenuItem updated = menuService.update(5L, TENANT_ID, "New Name", "Snacks",
                new BigDecimal("20.00"), null, null, null, ACTOR_ID);

        assertThat(updated.getPhotoPath()).isEqualTo("/uploads/menu-photos/old.png");
        verifyNoInteractions(fileStorageService);
    }

    @Test
    void updateReplacesThePhotoWhenANewOneIsProvided() {
        MenuItem existing = MenuItem.builder().id(5L).tenantId(TENANT_ID).outletId(OUTLET_ID)
                .name("Old Name").category("Snacks").photoPath("/uploads/menu-photos/old.png")
                .price(new BigDecimal("20.00")).available(true).build();
        when(menuItemDao.findByIdAndTenantId(5L, TENANT_ID)).thenReturn(Optional.of(existing));
        when(menuItemDao.save(any(MenuItem.class))).thenAnswer(inv -> inv.getArgument(0));
        MockMultipartFile photo = new MockMultipartFile("photo", "new.png", "image/png", new byte[]{1, 2, 3});
        when(fileStorageService.storeMenuItemPhoto(TENANT_ID, photo)).thenReturn("/uploads/menu-photos/new.png");

        MenuItem updated = menuService.update(5L, TENANT_ID, "New Name", "Snacks",
                new BigDecimal("20.00"), null, null, photo, ACTOR_ID);

        assertThat(updated.getPhotoPath()).isEqualTo("/uploads/menu-photos/new.png");
    }

    @Test
    void setAvailabilityAuditsBeforeAndAfterState() {
        MenuItem existing = MenuItem.builder().id(5L).tenantId(TENANT_ID).outletId(OUTLET_ID)
                .name("Samosa").category("Snacks").price(BigDecimal.TEN).available(true).build();
        when(menuItemDao.findByIdAndTenantId(5L, TENANT_ID)).thenReturn(Optional.of(existing));

        menuService.setAvailability(5L, TENANT_ID, false, ACTOR_ID);

        verify(menuItemDao).updateAvailability(5L, TENANT_ID, false);
        verify(auditService).record(ACTOR_ID, TENANT_ID, "MenuItem", 5L, "MARK_UNAVAILABLE", true, false);
    }

    @Test
    void deleteRequiresTheItemToExistInThisTenantFirst() {
        when(menuItemDao.findByIdAndTenantId(5L, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> menuService.delete(5L, TENANT_ID, ACTOR_ID))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(menuItemDao, never()).delete(any(), any());
    }
}
