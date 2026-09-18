package com.bitesite.service;

import com.bitesite.dao.CategoryDao;
import com.bitesite.dao.CategoryImageDao;
import com.bitesite.dao.OutletDao;
import com.bitesite.dao.PlatformSettingsDao;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.Outlet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The "All Dishes" chip's fallback chain: the outlet's own picture, then the platform
 * default, then null (the template's bundled illustration).
 */
@ExtendWith(MockitoExtension.class)
class CategoryImageServiceTest {

    @Mock private CategoryImageDao categoryImageDao;
    @Mock private CategoryDao categoryDao;
    @Mock private OutletDao outletDao;
    @Mock private PlatformSettingsDao platformSettingsDao;
    @Mock private FileStorageService fileStorageService;

    private CategoryImageService service;

    private static final Long TENANT_ID = 1L;
    private static final Long OUTLET_ID = 10L;
    private static final String KEY = CategoryImageService.ALL_DISHES_DEFAULT_KEY;

    @BeforeEach
    void setUp() {
        service = new CategoryImageService(categoryImageDao, categoryDao, outletDao, platformSettingsDao,
                fileStorageService);
        lenient().when(categoryImageDao.findAllDefaults()).thenReturn(List.of());
        // A recognisable rewrite, so a test can tell the chip got the thumbnail and not the original.
        lenient().when(fileStorageService.thumbnailUrl(anyString(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(0) + "?w=" + inv.getArgument(1));
    }

    private static Outlet outlet(String allDishesPath) {
        return Outlet.builder().id(OUTLET_ID).tenantId(TENANT_ID).name("Main")
                .allDishesImagePath(allDishesPath).build();
    }

    private void platformDefault(String path) {
        Map<String, String> settings = new HashMap<>();
        settings.put(KEY, path);
        when(platformSettingsDao.findAll()).thenReturn(settings);
    }

    @Test
    void outletsOwnPictureWinsWithoutEvenReadingThePlatformDefault() {
        assertThat(service.allDishesChipImage(outlet("/uploads/own.webp"))).isEqualTo("/uploads/own.webp?w=160");
        verifyNoInteractions(platformSettingsDao);
    }

    @Test
    void fallsBackToThePlatformDefaultWhenTheOutletHasNone() {
        platformDefault("/uploads/platform.webp");

        assertThat(service.allDishesChipImage(outlet(null))).isEqualTo("/uploads/platform.webp?w=160");
    }

    @Test
    void nullWhenNeitherIsSetSoTheTemplateDrawsTheBundledIllustration() {
        when(platformSettingsDao.findAll()).thenReturn(Map.of());

        assertThat(service.allDishesChipImage(outlet(null))).isNull();
    }

    @Test
    void aClearedOrBlankPlatformDefaultCountsAsUnset() {
        // Clearing writes NULL; a blank value must not become <img src="">.
        platformDefault("  ");

        assertThat(service.allDishesChipImage(outlet(null))).isNull();
        assertThat(service.defaultAllDishesImage()).isNull();
    }

    @Test
    void settingThePlatformDefaultIsVisibleImmediatelyDespiteTheCache() {
        Map<String, String> settings = new HashMap<>();
        when(platformSettingsDao.findAll()).thenReturn(settings);
        assertThat(service.defaultAllDishesImage()).isNull();   // now cached as "none"

        MockMultipartFile file = new MockMultipartFile("image", "a.png", "image/png", new byte[] {1});
        when(fileStorageService.storeCategoryImage(null, file)).thenReturn("/uploads/new.webp");
        doAnswer(inv -> settings.put(inv.getArgument(0), inv.getArgument(1)))
                .when(platformSettingsDao).upsert(anyString(), any());

        service.setDefaultAllDishesImage(file);

        assertThat(service.defaultAllDishesImage()).isEqualTo("/uploads/new.webp");
        verify(platformSettingsDao).upsert(KEY, "/uploads/new.webp");
    }

    @Test
    void clearingThePlatformDefaultWritesNullAndDropsTheCache() {
        Map<String, String> settings = new HashMap<>();
        settings.put(KEY, "/uploads/old.webp");
        when(platformSettingsDao.findAll()).thenReturn(settings);
        assertThat(service.defaultAllDishesImage()).isEqualTo("/uploads/old.webp");
        doAnswer(inv -> settings.put(inv.getArgument(0), inv.getArgument(1)))
                .when(platformSettingsDao).upsert(anyString(), any());

        service.clearDefaultAllDishesImage();

        verify(platformSettingsDao).upsert(KEY, null);
        assertThat(service.defaultAllDishesImage()).isNull();
    }

    @Test
    void managementScreenDistinguishesOwnFromInherited() {
        when(outletDao.findByIdAndTenantId(OUTLET_ID, TENANT_ID)).thenReturn(Optional.of(outlet("/uploads/own.webp")));
        assertThat(service.allDishesImageForOutlet(OUTLET_ID, TENANT_ID))
                .isEqualTo(new CategoryImageService.AllDishesImage("/uploads/own.webp", true));

        when(outletDao.findByIdAndTenantId(OUTLET_ID, TENANT_ID)).thenReturn(Optional.of(outlet(null)));
        platformDefault("/uploads/platform.webp");
        assertThat(service.allDishesImageForOutlet(OUTLET_ID, TENANT_ID))
                .isEqualTo(new CategoryImageService.AllDishesImage("/uploads/platform.webp", false));
    }

    @Test
    void outletUploadIsStoredAgainstTheSignedInTenantsOutlet() {
        when(outletDao.findByIdAndTenantId(OUTLET_ID, TENANT_ID)).thenReturn(Optional.of(outlet(null)));
        MockMultipartFile file = new MockMultipartFile("image", "a.png", "image/png", new byte[] {1});
        when(fileStorageService.storeCategoryImage(TENANT_ID, file)).thenReturn("/uploads/own.webp");

        service.setOutletAllDishesImage(OUTLET_ID, TENANT_ID, file);

        verify(outletDao).updateAllDishesImagePath(OUTLET_ID, TENANT_ID, "/uploads/own.webp");
    }

    @Test
    void anOutletFromAnotherCollegeIsNotFoundAndNothingIsStored() {
        when(outletDao.findByIdAndTenantId(OUTLET_ID, 2L)).thenReturn(Optional.empty());
        MockMultipartFile file = new MockMultipartFile("image", "a.png", "image/png", new byte[] {1});

        assertThatThrownBy(() -> service.setOutletAllDishesImage(OUTLET_ID, 2L, file))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(fileStorageService, never()).storeCategoryImage(any(), any());
        verify(outletDao, never()).updateAllDishesImagePath(anyLong(), anyLong(), any());
    }
}
