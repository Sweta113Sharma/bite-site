package com.bitesite.service;

import com.bitesite.dao.OutletDao;
import com.bitesite.dao.UserDao;
import com.bitesite.exception.BusinessException;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.Outlet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutletServiceTest {

    @Mock private OutletDao outletDao;
    @Mock private UserDao userDao;
    @Mock private AuditService auditService;

    private OutletService outletService;

    private static final Long TENANT_ID = 1L;
    private static final Long OUTLET_ID = 10L;
    private static final Long ACTOR_ID = 100L;

    @BeforeEach
    void setUp() {
        outletService = new OutletService(outletDao, userDao, auditService);
    }

    private Outlet existing(boolean active, boolean acceptingOrders) {
        return Outlet.builder().id(OUTLET_ID).tenantId(TENANT_ID).name("Main Canteen")
                .active(active).acceptingOrders(acceptingOrders).build();
    }

    private void outletExists(Outlet outlet) {
        when(outletDao.findByIdAndTenantId(OUTLET_ID, TENANT_ID)).thenReturn(Optional.of(outlet));
    }

    @Test
    void getIsScopedToTheTenant() {
        when(outletDao.findByIdAndTenantId(OUTLET_ID, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> outletService.get(OUTLET_ID, TENANT_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void renameKeepsBothStatusFlagsAndAuditsTheOldName() {
        outletExists(existing(true, false));
        ArgumentCaptor<Outlet> captor = ArgumentCaptor.forClass(Outlet.class);
        when(outletDao.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        outletService.rename(OUTLET_ID, TENANT_ID, "North Block Canteen", ACTOR_ID);

        Outlet saved = captor.getValue();
        assertThat(saved.getName()).isEqualTo("North Block Canteen");
        // A rename must not quietly un-pause an outlet or bring a disabled one back.
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.isAcceptingOrders()).isFalse();
        verify(auditService).record(ACTOR_ID, TENANT_ID, "Outlet", OUTLET_ID, "RENAME",
                "Main Canteen", "North Block Canteen");
    }

    @Test
    void deactivatingLeavesThePauseFlagAlone() {
        outletExists(existing(true, true));
        ArgumentCaptor<Outlet> captor = ArgumentCaptor.forClass(Outlet.class);
        when(outletDao.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        outletService.setActive(OUTLET_ID, TENANT_ID, false, ACTOR_ID);

        assertThat(captor.getValue().isActive()).isFalse();
        assertThat(captor.getValue().isAcceptingOrders()).isTrue();
        verify(auditService).record(ACTOR_ID, TENANT_ID, "Outlet", OUTLET_ID, "DEACTIVATE", true, false);
    }

    @Test
    void pausingOrdersAudits() {
        outletExists(existing(true, true));

        outletService.setAcceptingOrders(OUTLET_ID, TENANT_ID, false, ACTOR_ID);

        verify(outletDao).updateAcceptingOrders(OUTLET_ID, TENANT_ID, false);
        verify(auditService).record(ACTOR_ID, TENANT_ID, "Outlet", OUTLET_ID, "PAUSE_ORDERS", true, false);
    }

    @Test
    void deleteIsRefusedOnceTheOutletHasTakenAnyOrder() {
        outletExists(existing(true, true));
        when(outletDao.countOrders(OUTLET_ID)).thenReturn(3);

        assertThatThrownBy(() -> outletService.delete(OUTLET_ID, TENANT_ID, ACTOR_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("3 orders")
                .hasMessageContaining("Deactivate it instead");

        verify(outletDao, never()).delete(anyLong(), anyLong());
        verify(userDao, never()).detachFromOutlet(anyLong());
        verify(auditService, never()).record(any(), any(), any(), any(), eq("DELETE"), any(), any());
    }

    @Test
    void deleteDetachesStaffAndRemovesTheOutletWhenNoOrdersExist() {
        Outlet outlet = existing(true, true);
        outletExists(outlet);
        when(outletDao.countOrders(OUTLET_ID)).thenReturn(0);
        when(outletDao.findStaffUserIds(OUTLET_ID)).thenReturn(List.of(7L, 8L));

        int deactivated = outletService.delete(OUTLET_ID, TENANT_ID, ACTOR_ID);

        assertThat(deactivated).isEqualTo(2);
        verify(userDao).detachFromOutlet(7L);
        verify(userDao).detachFromOutlet(8L);
        verify(outletDao).delete(OUTLET_ID, TENANT_ID);
        verify(auditService).record(ACTOR_ID, TENANT_ID, "Outlet", OUTLET_ID, "DELETE", outlet, null);
    }

    @Test
    void deleteOfAnOutletFromAnotherTenantIsNotFound() {
        when(outletDao.findByIdAndTenantId(OUTLET_ID, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> outletService.delete(OUTLET_ID, TENANT_ID, ACTOR_ID))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(outletDao, never()).delete(anyLong(), anyLong());
    }

    @Test
    void newOutletsTakeOrdersFromTheStart() {
        ArgumentCaptor<Outlet> captor = ArgumentCaptor.forClass(Outlet.class);
        when(outletDao.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        outletService.create(TENANT_ID, "New Canteen");

        assertThat(captor.getValue().isActive()).isTrue();
        assertThat(captor.getValue().isAcceptingOrders()).isTrue();
    }
}
