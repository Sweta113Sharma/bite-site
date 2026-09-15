package com.bitesite.controller.student;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.model.BillingSettings;
import com.bitesite.model.MenuItem;
import com.bitesite.model.Role;
import com.bitesite.model.User;
import com.bitesite.service.BillingService;
import com.bitesite.service.Cart;
import com.bitesite.service.CartPersistence;
import com.bitesite.service.MenuService;
import com.bitesite.service.OutletService;
import com.bitesite.service.PromoCodeService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartControllerTest {

    @Mock private MenuService menuService;
    @Mock private CartPersistence cartPersistence;
    @Mock private OutletService outletService;
    @Mock private BillingService billingService;
    @Mock private PromoCodeService promoCodeService;

    private Cart cart;
    private CartController controller;
    private ObjectMapper objectMapper;
    private AppUserPrincipal principal;

    @BeforeEach
    void setUp() {
        cart = new Cart(new MockHttpServletRequest());
        objectMapper = new ObjectMapper();
        controller = new CartController(
                cart, menuService, cartPersistence, outletService,
                objectMapper, billingService, promoCodeService
        );

        User user = User.builder().id(10L).tenantId(1L).email("student@test.local").role(Role.USER).build();
        principal = new AppUserPrincipal(user);
    }

    @Test
    void updateQuantityReturnsFullCartSummaryWhenJsonRequested() throws Exception {
        cart.ensureOutlet(5L);
        cart.add(101L, 2);

        MenuItem item = MenuItem.builder().id(101L).price(new BigDecimal("90.00")).build();
        when(menuService.get(eq(101L), anyLong())).thenReturn(item);
        when(billingService.settings()).thenReturn(BillingSettings.from(Map.of(
                BillingSettings.FEE_CHARGE, "true",
                BillingSettings.FEE_AMOUNT, "5.00"
        )));

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.update(principal, 101L, 1, MediaType.APPLICATION_JSON_VALUE, response);

        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = objectMapper.readValue(response.getContentAsString(), new TypeReference<>() {});

        assertThat(body.get("count")).isEqualTo(1);
        assertThat(body.get("quantity")).isEqualTo(1);
        assertThat(new BigDecimal(body.get("lineTotal").toString())).isEqualByComparingTo("90.00");
        assertThat(new BigDecimal(body.get("itemTotal").toString())).isEqualByComparingTo("90.00");
        assertThat(new BigDecimal(body.get("fee").toString())).isEqualByComparingTo("5.00");
        assertThat(new BigDecimal(body.get("grandTotal").toString())).isEqualByComparingTo("95.00");
        assertThat(body.get("empty")).isEqualTo(false);
    }

    @Test
    void removeItemReturnsFullCartSummaryAndEmptyFlag() throws Exception {
        cart.ensureOutlet(5L);
        cart.add(101L, 1);

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.remove(principal, 101L, MediaType.APPLICATION_JSON_VALUE, response);

        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = objectMapper.readValue(response.getContentAsString(), new TypeReference<>() {});

        assertThat(body.get("count")).isEqualTo(0);
        assertThat(body.get("quantity")).isEqualTo(0);
        assertThat(new BigDecimal(body.get("total").toString())).isEqualByComparingTo("0");
        assertThat(body.get("empty")).isEqualTo(true);
    }

    /** The in-place update used to drop the code silently, leaving the applied-code row on
     * screen. The flag makes the page reload, and the cart render explains the removal. */
    @Test
    void aQuantityChangeThatBreaksThePromoFlagsItAndLeavesTheCodeForTheCartPageToExplain() throws Exception {
        cart.ensureOutlet(5L);
        cart.add(101L, 2);
        cart.setPromoCode("LUNCH50");

        when(menuService.get(eq(101L), anyLong()))
                .thenReturn(MenuItem.builder().id(101L).price(new BigDecimal("90.00")).build());
        when(promoCodeService.validate(eq("LUNCH50"), eq(10L), eq(1L), eq(5L), eq(new BigDecimal("90.00"))))
                .thenThrow(new com.bitesite.exception.BusinessException("This code needs a cart of at least ₹150."));

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.update(principal, 101L, 1, MediaType.APPLICATION_JSON_VALUE, response);

        Map<String, Object> body = objectMapper.readValue(response.getContentAsString(), new TypeReference<>() {});
        assertThat(body.get("promoNoLongerApplies")).isEqualTo(true);
        assertThat(new BigDecimal(body.get("discount").toString())).isEqualByComparingTo("0");
        assertThat(cart.getPromoCode()).isEqualTo("LUNCH50");
    }
}
