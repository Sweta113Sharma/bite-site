package com.bitesite.service;

import com.bitesite.dao.UserDao;
import com.bitesite.exception.BusinessException;
import com.bitesite.exception.DuplicateEmailException;
import com.bitesite.model.Role;
import com.bitesite.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.EnumSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserDao userDao;
    @Mock private AuditService auditService;
    @Mock private EmailService emailService;
    @Mock private SmsService smsService;
    @Mock private PushNotificationService pushNotificationService;

    // A real encoder, not a mock — this is exactly the kind of "does the password actually
    // verify afterward" property a mock would silently paper over.
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userDao, passwordEncoder, auditService, emailService, smsService,
                pushNotificationService);
    }

    @Test
    void registerStudentRejectsADuplicateEmail() {
        when(userDao.existsByEmail("taken@demo.local")).thenReturn(true);

        assertThatThrownBy(() -> userService.registerStudent(1L, "A Student", "taken@demo.local",
                "Password1!", null, null))
                .isInstanceOf(DuplicateEmailException.class);
        verify(userDao, never()).save(any());
    }

    @Test
    void registerStudentNormalizesEmailAndHashesAVerifiablePassword() {
        when(userDao.existsByEmail("student@demo.local")).thenReturn(false);
        when(emailService.isConfigured()).thenReturn(false);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        when(userDao.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        userService.registerStudent(1L, "  A Student  ", "  Student@Demo.LOCAL  ", "Password1!",
                "9999999999", "CSE-101");

        User saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo("student@demo.local");
        assertThat(saved.getName()).isEqualTo("A Student");
        assertThat(saved.getRole()).isEqualTo(Role.USER);
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getPasswordHash()).isNotEqualTo("Password1!");
        assertThat(passwordEncoder.matches("Password1!", saved.getPasswordHash())).isTrue();
    }

    @Test
    void registerStudentIsAutoVerifiedWhenSmtpIsNotConfigured() {
        when(userDao.existsByEmail("student2@demo.local")).thenReturn(false);
        when(emailService.isConfigured()).thenReturn(false);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        when(userDao.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        userService.registerStudent(1L, "A Student", "student2@demo.local", "Password1!", null, null);

        assertThat(captor.getValue().isEmailVerified()).isTrue();
    }

    @Test
    void registerStudentIsUnverifiedWhenSmtpIsConfigured() {
        when(userDao.existsByEmail("student3@demo.local")).thenReturn(false);
        when(emailService.isConfigured()).thenReturn(true);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        when(userDao.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        userService.registerStudent(1L, "A Student", "student3@demo.local", "Password1!", null, null);

        assertThat(captor.getValue().isEmailVerified()).isFalse();
    }

    @Test
    void registerStudentIsPhoneAutoVerifiedWhenSmsIsNotConfigured() {
        when(userDao.existsByEmail("student4@demo.local")).thenReturn(false);
        when(emailService.isConfigured()).thenReturn(false);
        when(smsService.isConfigured()).thenReturn(false);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        when(userDao.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        userService.registerStudent(1L, "A Student", "student4@demo.local", "Password1!", "9999999999", null);

        assertThat(captor.getValue().isPhoneVerified()).isTrue();
    }

    @Test
    void registerStudentIsPhoneUnverifiedWhenSmsIsConfiguredAndAPhoneWasGiven() {
        when(userDao.existsByEmail("student5@demo.local")).thenReturn(false);
        when(emailService.isConfigured()).thenReturn(false);
        when(smsService.isConfigured()).thenReturn(true);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        when(userDao.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        userService.registerStudent(1L, "A Student", "student5@demo.local", "Password1!", "9999999999", null);

        assertThat(captor.getValue().isPhoneVerified()).isFalse();
    }

    @Test
    void registerStudentIsPhoneAutoVerifiedWhenNoPhoneWasGiven() {
        when(userDao.existsByEmail("student6@demo.local")).thenReturn(false);
        when(emailService.isConfigured()).thenReturn(false);
        // No isConfigured() stub: registerStudent must short-circuit on "no phone given"
        // without ever asking SmsService, which this proves by never stubbing an answer.
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        when(userDao.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        userService.registerStudent(1L, "A Student", "student6@demo.local", "Password1!", null, null);

        assertThat(captor.getValue().isPhoneVerified()).isTrue();
    }

    @Test
    void createUserAssignsTheRequestedRoleAndOutlet() {
        when(userDao.existsByEmail("canteen@demo.local")).thenReturn(false);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        when(userDao.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        userService.createUser(1L, 10L, "Canteen Staff", "canteen@demo.local", "Password1!", Role.CANTEEN_STAFF);

        User saved = captor.getValue();
        assertThat(saved.getRole()).isEqualTo(Role.CANTEEN_STAFF);
        assertThat(saved.getOutletId()).isEqualTo(10L);
        assertThat(saved.getTenantId()).isEqualTo(1L);
        assertThat(saved.isPhoneVerified()).isTrue();
        assertThat(saved.isEmailVerified()).isTrue();
        verifyNoInteractions(emailService);
    }

    @Test
    void createUserRejectsADuplicateEmailToo() {
        when(userDao.existsByEmail("dup@demo.local")).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser(1L, 10L, "X", "dup@demo.local", "Password1!", Role.CANTEEN_STAFF))
                .isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    void deleteOwnAccountAnonymizesTheUserAndRecordsAnAuditEntry() {
        ArgumentCaptor<String> emailCaptor = ArgumentCaptor.forClass(String.class);

        userService.deleteOwnAccount(42L, 1L);

        verify(userDao).anonymize(eq(42L), emailCaptor.capture(), anyString());
        assertThat(emailCaptor.getValue()).matches("deleted-42-\\d+@deleted\\.bitesite\\.local");
        verify(auditService).record(eq(42L), eq(1L), eq("User"), eq(42L), eq("SELF_DELETE_ACCOUNT"), isNull(), isNull());
    }

    @Test
    void deleteOwnAccountAlsoDropsEveryPushSubscription() {
        // The users row survives anonymisation, so the FK from push_subscriptions stays
        // valid and the device carries on receiving notifications for a "deleted" account.
        userService.deleteOwnAccount(42L, 1L);

        verify(pushNotificationService).unsubscribeAll(42L);
    }

    @Test
    void grantRoleDelegatesStraightToTheDao() {
        userService.grantRole(7L, Role.TECH_MANAGER, 1L);

        verify(userDao).grantRole(7L, Role.TECH_MANAGER, 1L);
    }

    @Test
    void revokeRoleRefusesToRemoveAUsersOnlyRemainingRole() {
        User user = User.builder().id(7L).activeRole(Role.SUPER_ADMIN)
                .roles(EnumSet.of(Role.SUPER_ADMIN)).build();
        when(userDao.findById(7L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.revokeRole(7L, Role.SUPER_ADMIN, 1L))
                .isInstanceOf(BusinessException.class);
        verify(userDao, never()).revokeRole(any(), any(), any());
    }

    @Test
    void revokingTheActiveRoleFallsBackToAnotherHeldRole() {
        User user = User.builder().id(7L).activeRole(Role.SUPER_ADMIN)
                .roles(EnumSet.of(Role.SUPER_ADMIN, Role.TECH_MANAGER)).build();
        when(userDao.findById(7L)).thenReturn(Optional.of(user));

        userService.revokeRole(7L, Role.SUPER_ADMIN, 1L);

        verify(userDao).revokeRole(7L, Role.SUPER_ADMIN, 1L);
        verify(userDao).updateActiveRole(7L, Role.TECH_MANAGER);
    }

    @Test
    void revokingANonActiveRoleDoesNotTouchTheActiveRole() {
        User user = User.builder().id(7L).activeRole(Role.SUPER_ADMIN)
                .roles(EnumSet.of(Role.SUPER_ADMIN, Role.TECH_MANAGER)).build();
        when(userDao.findById(7L)).thenReturn(Optional.of(user));

        userService.revokeRole(7L, Role.TECH_MANAGER, 1L);

        verify(userDao).revokeRole(7L, Role.TECH_MANAGER, 1L);
        verify(userDao, never()).updateActiveRole(any(), any());
    }
}
