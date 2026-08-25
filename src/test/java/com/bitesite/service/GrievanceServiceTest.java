package com.bitesite.service;

import com.bitesite.dao.GrievanceDao;
import com.bitesite.dao.OrderDao;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.Grievance;
import com.bitesite.model.GrievanceStatus;
import com.bitesite.model.Order;
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
class GrievanceServiceTest {

    @Mock private GrievanceDao grievanceDao;
    @Mock private AuditService auditService;
    @Mock private OrderDao orderDao;

    private GrievanceService grievanceService;

    private static final Long TENANT_ID = 1L;
    private static final Long ACTOR_ID = 100L;

    @BeforeEach
    void setUp() {
        grievanceService = new GrievanceService(grievanceDao, orderDao, auditService);
    }

    @Test
    void raiseStartsOpen() {
        when(grievanceDao.save(any())).thenAnswer(inv -> {
            Grievance g = inv.getArgument(0);
            g.setId(1L);
            return g;
        });

        Grievance g = grievanceService.raise(TENANT_ID, 50L, null, "Wrong item", "I got the wrong order");

        assertThat(g.getStatus()).isEqualTo(GrievanceStatus.OPEN);
        assertThat(g.getRaisedByUserId()).isEqualTo(50L);
        assertThat(g.getOrderId()).isNull();
        verifyNoInteractions(orderDao);
    }

    @Test
    void raiseAttachesAnOrderTheStudentOwns() {
        Order own = Order.builder().id(9L).tenantId(TENANT_ID).userId(50L).build();
        when(orderDao.findByIdAndTenantId(9L, TENANT_ID)).thenReturn(Optional.of(own));
        when(grievanceDao.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Grievance g = grievanceService.raise(TENANT_ID, 50L, 9L, "Wrong item", "...");

        assertThat(g.getOrderId()).isEqualTo(9L);
    }

    @Test
    void raiseRefusesAnOrderBelongingToAnotherStudent() {
        // orderId comes off a form field, so a student could post someone else's id and
        // have its token and total rendered back to them from the support screen.
        Order someoneElses = Order.builder().id(9L).tenantId(TENANT_ID).userId(999L).build();
        when(orderDao.findByIdAndTenantId(9L, TENANT_ID)).thenReturn(Optional.of(someoneElses));

        assertThatThrownBy(() -> grievanceService.raise(TENANT_ID, 50L, 9L, "Wrong item", "..."))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(grievanceDao, never()).save(any());
    }

    @Test
    void raiseRefusesAnOrderFromAnotherTenant() {
        when(orderDao.findByIdAndTenantId(9L, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> grievanceService.raise(TENANT_ID, 50L, 9L, "Wrong item", "..."))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(grievanceDao, never()).save(any());
    }

    @Test
    void resolveRequiresTheGrievanceToExist() {
        when(grievanceDao.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> grievanceService.resolve(99L, "response", ACTOR_ID))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(grievanceDao, never()).resolve(any(), any(), any(), any());
    }

    @Test
    void resolveDerivesTheTenantFromTheGrievanceNotFromTheCaller() {
        // The admin resolving this doesn't supply a tenantId — it's a cross-tenant inbox —
        // so the tenant used for the update and audit trail must come from the grievance
        // record itself, not be guessable/spoofable by the caller.
        Grievance existing = Grievance.builder().id(1L).tenantId(7L).raisedByUserId(50L)
                .subject("Wrong item").message("...").status(GrievanceStatus.OPEN).build();
        when(grievanceDao.findById(1L)).thenReturn(Optional.of(existing));

        grievanceService.resolve(1L, "Refunded", ACTOR_ID);

        verify(grievanceDao).resolve(1L, 7L, "Refunded", GrievanceStatus.RESOLVED);
        verify(auditService).record(ACTOR_ID, 7L, "Grievance", 1L, "RESOLVE", GrievanceStatus.OPEN, GrievanceStatus.RESOLVED);
    }
}
