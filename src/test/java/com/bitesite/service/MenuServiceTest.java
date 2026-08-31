package com.bitesite.service;

import com.bitesite.dao.MenuItemDao;
import com.bitesite.dao.OrderDao;
import com.bitesite.dto.MenuItemForm;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MenuServiceTest {

    @Mock private MenuItemDao menuItemDao;
    @Mock private OrderDao orderDao;
    @Mock private AuditService auditService;
    @Mock private FileStorageService fileStorageService;

    private MenuService menuService;

    private static final Long TENANT_ID = 1L;
    private static final Long OUTLET_ID = 10L;
    private static final Long ACTOR_ID = 100L;
    private static final long CATEGORY_ID = 55L;

    @BeforeEach
    void setUp() {
        menuService = new MenuService(menuItemDao, orderDao, auditService, fileStorageService);
    }

    /** {@code category} is now a category id rather than free text — see V18. The label is
     * kept as a parameter so each test still reads as "a form for a snack". */
    private MenuItemForm form(String name, long categoryId, String price) {
        MenuItemForm form = new MenuItemForm();
        form.setName(name);
        form.setCategoryId(categoryId);
        form.setPrice(new BigDecimal(price));
        return form;
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

        MenuItem saved = menuService.create(TENANT_ID, OUTLET_ID, form("Samosa", CATEGORY_ID, "30.00"), null, ACTOR_ID);

        assertThat(saved.isAvailable()).isTrue();
        assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(saved.getPhotoPath()).isNull();
        assertThat(saved.getDailyLimit()).isNull();
        verify(auditService).record(eq(ACTOR_ID), eq(TENANT_ID), eq("MenuItem"), eq(42L), eq("CREATE"), isNull(), eq(saved));
        verifyNoInteractions(fileStorageService);
    }

    @Test
    void createCarriesTheDailyLimitThrough() {
        when(menuItemDao.save(any(MenuItem.class))).thenAnswer(inv -> inv.getArgument(0));
        MenuItemForm form = form("Masala Dosa", CATEGORY_ID, "60.00");
        form.setDailyLimit(40);

        MenuItem saved = menuService.create(TENANT_ID, OUTLET_ID, form, null, ACTOR_ID);

        assertThat(saved.getDailyLimit()).isEqualTo(40);
    }

    @Test
    void createUploadsAndStoresThePhotoPathWhenAPhotoIsProvided() {
        when(menuItemDao.save(any(MenuItem.class))).thenAnswer(inv -> inv.getArgument(0));
        MockMultipartFile photo = new MockMultipartFile("photo", "samosa.png", "image/png", new byte[]{1, 2, 3});
        when(fileStorageService.storeMenuItemPhoto(TENANT_ID, photo)).thenReturn("/uploads/menu-photos/samosa.png");

        MenuItem saved = menuService.create(TENANT_ID, OUTLET_ID, form("Samosa", CATEGORY_ID, "30.00"), photo, ACTOR_ID);

        assertThat(saved.getPhotoPath()).isEqualTo("/uploads/menu-photos/samosa.png");
    }

    @Test
    void updatePreservesOutletAndAvailabilityFromTheExistingRecord() {
        MenuItem existing = MenuItem.builder().id(5L).tenantId(TENANT_ID).outletId(OUTLET_ID)
                .name("Old Name").category("Snacks").price(new BigDecimal("20.00")).available(false).build();
        when(menuItemDao.findByIdAndTenantId(5L, TENANT_ID)).thenReturn(Optional.of(existing));

        ArgumentCaptor<MenuItem> captor = ArgumentCaptor.forClass(MenuItem.class);
        when(menuItemDao.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        menuService.update(5L, TENANT_ID, form("New Name", CATEGORY_ID, "50.00"), null, ACTOR_ID);

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

        MenuItem updated = menuService.update(5L, TENANT_ID, form("New Name", CATEGORY_ID, "20.00"), null, ACTOR_ID);

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

        MenuItem updated = menuService.update(5L, TENANT_ID, form("New Name", CATEGORY_ID, "20.00"), photo, ACTOR_ID);

        assertThat(updated.getPhotoPath()).isEqualTo("/uploads/menu-photos/new.png");
    }

    @Test
    void updateDropsThePhotoWhenRemoveIsTicked() {
        MenuItem existing = MenuItem.builder().id(5L).tenantId(TENANT_ID).outletId(OUTLET_ID)
                .name("Samosa").category("Snacks").photoPath("/uploads/menu-photos/old.png")
                .price(new BigDecimal("20.00")).available(true).build();
        when(menuItemDao.findByIdAndTenantId(5L, TENANT_ID)).thenReturn(Optional.of(existing));
        when(menuItemDao.save(any(MenuItem.class))).thenAnswer(inv -> inv.getArgument(0));
        MenuItemForm form = form("Samosa", CATEGORY_ID, "20.00");
        form.setRemovePhoto(true);

        MenuItem updated = menuService.update(5L, TENANT_ID, form, null, ACTOR_ID);

        assertThat(updated.getPhotoPath()).isNull();
        verifyNoInteractions(fileStorageService);
    }

    @Test
    void updateIgnoresRemovePhotoWhenAReplacementIsUploadedInTheSameSubmit() {
        MenuItem existing = MenuItem.builder().id(5L).tenantId(TENANT_ID).outletId(OUTLET_ID)
                .name("Samosa").category("Snacks").photoPath("/uploads/menu-photos/old.png")
                .price(new BigDecimal("20.00")).available(true).build();
        when(menuItemDao.findByIdAndTenantId(5L, TENANT_ID)).thenReturn(Optional.of(existing));
        when(menuItemDao.save(any(MenuItem.class))).thenAnswer(inv -> inv.getArgument(0));
        MockMultipartFile photo = new MockMultipartFile("photo", "new.png", "image/png", new byte[]{1, 2, 3});
        when(fileStorageService.storeMenuItemPhoto(TENANT_ID, photo)).thenReturn("/uploads/menu-photos/new.png");
        MenuItemForm form = form("Samosa", CATEGORY_ID, "20.00");
        form.setRemovePhoto(true);

        MenuItem updated = menuService.update(5L, TENANT_ID, form, photo, ACTOR_ID);

        assertThat(updated.getPhotoPath()).isEqualTo("/uploads/menu-photos/new.png");
    }

    @Test
    void listForOutletAttachesTodaysOrderedCountToEachItem() {
        MenuItem dosa = MenuItem.builder().id(1L).tenantId(TENANT_ID).outletId(OUTLET_ID)
                .name("Dosa").categoryId(CATEGORY_ID).price(BigDecimal.TEN).available(true).dailyLimit(30).build();
        MenuItem chai = MenuItem.builder().id(2L).tenantId(TENANT_ID).outletId(OUTLET_ID)
                .name("Chai").categoryId(CATEGORY_ID).price(BigDecimal.ONE).available(true).build();
        when(menuItemDao.findByOutletId(OUTLET_ID, TENANT_ID)).thenReturn(List.of(dosa, chai));
        when(orderDao.sumQuantitiesByMenuItemToday(TENANT_ID, OUTLET_ID)).thenReturn(Map.of(1L, 28));

        List<MenuItem> items = menuService.listForOutlet(OUTLET_ID, TENANT_ID);

        assertThat(items.get(0).getSoldToday()).isEqualTo(28);
        assertThat(items.get(0).remainingToday()).isEqualTo(2);
        assertThat(items.get(0).runningLowToday()).isTrue();
        // No entry in the roll-up means none ordered today, not "unknown".
        assertThat(items.get(1).getSoldToday()).isZero();
        assertThat(items.get(1).remainingToday()).isNull();
    }

    @Test
    void listForOutletSkipsTheRollUpQueryForAnEmptyMenu() {
        when(menuItemDao.findByOutletId(OUTLET_ID, TENANT_ID)).thenReturn(List.of());

        assertThat(menuService.listForOutlet(OUTLET_ID, TENANT_ID)).isEmpty();
        verifyNoInteractions(orderDao);
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
    void markAllAvailableDoesNotAuditWhenNothingWasOutOfStock() {
        when(menuItemDao.markAllAvailable(OUTLET_ID, TENANT_ID)).thenReturn(0);

        assertThat(menuService.markAllAvailable(OUTLET_ID, TENANT_ID, ACTOR_ID)).isZero();
        verifyNoInteractions(auditService);
    }

    @Test
    void markAllAvailableAuditsHowManyItemsCameBack() {
        when(menuItemDao.markAllAvailable(OUTLET_ID, TENANT_ID)).thenReturn(7);

        assertThat(menuService.markAllAvailable(OUTLET_ID, TENANT_ID, ACTOR_ID)).isEqualTo(7);
        verify(auditService).record(eq(ACTOR_ID), eq(TENANT_ID), eq("Outlet"), eq(OUTLET_ID),
                eq("MENU_MARK_ALL_AVAILABLE"), isNull(), eq("7 items"));
    }

    @Test
    void deleteRequiresTheItemToExistInThisTenantFirst() {
        when(menuItemDao.findByIdAndTenantId(5L, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> menuService.delete(5L, TENANT_ID, ACTOR_ID))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(menuItemDao, never()).delete(any(), any());
    }
}
