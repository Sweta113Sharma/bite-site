package com.bitesite.controller.canteen;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.model.MenuItem;
import com.bitesite.model.User;
import com.bitesite.model.Role;
import com.bitesite.service.CategoryService;
import com.bitesite.service.MenuService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MenuControllerTest {

    @Mock private MenuService menuService;
    @Mock private CategoryService categoryService;

    private MenuController controller;

    private AppUserPrincipal operatorPrincipal;

    @BeforeEach
    void setUp() {
        controller = new MenuController(menuService, categoryService);
        User operator = User.builder()
                .id(101L)
                .tenantId(1L)
                .outletId(5L)
                .role(Role.CANTEEN_OPERATOR)
                .activeRole(Role.CANTEEN_OPERATOR)
                .build();
        operatorPrincipal = new AppUserPrincipal(operator);
    }

    @Test
    void toggleAvailabilityRestocksAnItemThatIsOutOfStockTodayAndAddsFlashNotice() {
        MenuItem item = MenuItem.builder()
                .id(42L)
                .tenantId(1L)
                .outletId(5L)
                .name("Cold Coffee")
                .available(true)
                .outOfStockToday(true)
                .build();
        when(menuService.get(42L, 1L)).thenReturn(item);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.toggleAvailability(operatorPrincipal, 42L, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/canteen/menu");
        verify(menuService).setAvailability(42L, 1L, true, 101L);
        assertThat(redirectAttributes.getFlashAttributes().get("menuNotice"))
                .isEqualTo("Cold Coffee is back on sale.");
    }

    @Test
    void toggleAvailabilityMarksAnAvailableItemOutOfStockAndAddsFlashNotice() {
        MenuItem item = MenuItem.builder()
                .id(42L)
                .tenantId(1L)
                .outletId(5L)
                .name("Cold Coffee")
                .available(true)
                .outOfStockToday(false)
                .build();
        when(menuService.get(42L, 1L)).thenReturn(item);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.toggleAvailability(operatorPrincipal, 42L, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/canteen/menu");
        verify(menuService).setAvailability(42L, 1L, false, 101L);
        assertThat(redirectAttributes.getFlashAttributes().get("menuNotice"))
                .isEqualTo("Cold Coffee is marked out of stock.");
    }

    @Test
    void restockAllRestoresAllItemsAndAddsFlashNotice() {
        when(menuService.markAllAvailable(5L, 1L, 101L)).thenReturn(3);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.restockAll(operatorPrincipal, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/canteen/menu");
        assertThat(redirectAttributes.getFlashAttributes().get("menuNotice"))
                .isEqualTo("3 items are back on sale.");
    }
}
