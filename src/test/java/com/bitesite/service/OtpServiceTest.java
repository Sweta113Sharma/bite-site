package com.bitesite.service;

import com.bitesite.dao.OtpCodeDao;
import com.bitesite.dao.UserDao;
import com.bitesite.model.OtpChannel;
import com.bitesite.model.OtpCode;
import com.bitesite.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OtpServiceTest {

    @Mock private OtpCodeDao otpCodeDao;
    @Mock private UserDao userDao;
    @Mock private EmailService emailService;
    @Mock private SmsService smsService;

    private OtpService service;

    @BeforeEach
    void setUp() {
        service = new OtpService(otpCodeDao, userDao, emailService, smsService);
    }

    private static String hash(String code) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes()));
    }

    // --- issueEmailOtp ---

    @Test
    void issueEmailOtpDoesNothingWhenSmtpIsNotConfigured() {
        when(emailService.isConfigured()).thenReturn(false);

        service.issueEmailOtp(User.builder().id(1L).name("A").email("a@demo.local").build());

        verifyNoInteractions(otpCodeDao);
        verify(emailService, never()).sendOtpEmail(anyString(), anyString(), anyString());
    }

    @Test
    void issueEmailOtpClearsOldCodesAndEmailsASixDigitCode() {
        when(emailService.isConfigured()).thenReturn(true);
        when(otpCodeDao.save(any())).thenAnswer(inv -> inv.getArgument(0));
        User user = User.builder().id(7L).name("A Student").email("student@demo.local").build();

        service.issueEmailOtp(user);

        verify(otpCodeDao).deleteByUserIdAndChannel(7L, OtpChannel.EMAIL);
        ArgumentCaptor<OtpCode> captor = ArgumentCaptor.forClass(OtpCode.class);
        verify(otpCodeDao).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(7L);
        assertThat(captor.getValue().getChannel()).isEqualTo(OtpChannel.EMAIL);
        assertThat(captor.getValue().getExpiresAt()).isAfter(LocalDateTime.now());

        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendOtpEmail(eq("student@demo.local"), eq("A Student"), codeCaptor.capture());
        assertThat(codeCaptor.getValue()).matches("\\d{6}");
    }

    // --- issuePhoneOtp ---

    @Test
    void issuePhoneOtpDoesNothingWhenSmsIsNotConfigured() {
        when(smsService.isConfigured()).thenReturn(false);

        service.issuePhoneOtp(User.builder().id(1L).phone("9999999999").build());

        verifyNoInteractions(otpCodeDao);
        verify(smsService, never()).sendOtp(anyString(), anyString());
    }

    @Test
    void issuePhoneOtpDoesNothingWhenNoPhoneWasProvided() {
        when(smsService.isConfigured()).thenReturn(true);

        service.issuePhoneOtp(User.builder().id(1L).phone(null).build());

        verifyNoInteractions(otpCodeDao);
        verify(smsService, never()).sendOtp(anyString(), anyString());
    }

    @Test
    void issuePhoneOtpSendsASixDigitCodeWhenConfiguredAndPhonePresent() {
        when(smsService.isConfigured()).thenReturn(true);
        when(otpCodeDao.save(any())).thenAnswer(inv -> inv.getArgument(0));
        User user = User.builder().id(7L).phone("9999999999").build();

        service.issuePhoneOtp(user);

        verify(otpCodeDao).deleteByUserIdAndChannel(7L, OtpChannel.PHONE);
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(smsService).sendOtp(eq("9999999999"), codeCaptor.capture());
        assertThat(codeCaptor.getValue()).matches("\\d{6}");
    }

    // --- verify ---

    @Test
    void verifyReturnsFalseWhenNoCodeWasEverIssued() {
        when(otpCodeDao.findLatest(1L, OtpChannel.EMAIL)).thenReturn(Optional.empty());

        assertThat(service.verify(1L, OtpChannel.EMAIL, "123456")).isFalse();
        verify(userDao, never()).markEmailVerified(any());
    }

    @Test
    void verifyReturnsFalseForAnExpiredCode() throws Exception {
        when(otpCodeDao.findLatest(1L, OtpChannel.EMAIL)).thenReturn(Optional.of(OtpCode.builder()
                .id(5L).userId(1L).channel(OtpChannel.EMAIL).codeHash(hash("123456"))
                .expiresAt(LocalDateTime.now().minusMinutes(1)).build()));

        assertThat(service.verify(1L, OtpChannel.EMAIL, "123456")).isFalse();
        verify(userDao, never()).markEmailVerified(any());
    }

    @Test
    void verifyReturnsFalseAndIncrementsAttemptsForAWrongCode() throws Exception {
        when(otpCodeDao.findLatest(1L, OtpChannel.EMAIL)).thenReturn(Optional.of(OtpCode.builder()
                .id(5L).userId(1L).channel(OtpChannel.EMAIL).codeHash(hash("123456"))
                .expiresAt(LocalDateTime.now().plusMinutes(5)).attempts(0).build()));

        assertThat(service.verify(1L, OtpChannel.EMAIL, "000000")).isFalse();
        verify(otpCodeDao).incrementAttempts(5L);
        verify(userDao, never()).markEmailVerified(any());
    }

    @Test
    void verifyReturnsFalseOnceTooManyWrongAttemptsHaveBeenMade() throws Exception {
        when(otpCodeDao.findLatest(1L, OtpChannel.EMAIL)).thenReturn(Optional.of(OtpCode.builder()
                .id(5L).userId(1L).channel(OtpChannel.EMAIL).codeHash(hash("123456"))
                .expiresAt(LocalDateTime.now().plusMinutes(5)).attempts(5).build()));

        assertThat(service.verify(1L, OtpChannel.EMAIL, "123456")).isFalse();
        verify(otpCodeDao, never()).incrementAttempts(any());
        verify(userDao, never()).markEmailVerified(any());
    }

    @Test
    void verifyMarksEmailVerifiedAndConsumesTheCodeOnAMatch() throws Exception {
        when(otpCodeDao.findLatest(3L, OtpChannel.EMAIL)).thenReturn(Optional.of(OtpCode.builder()
                .id(9L).userId(3L).channel(OtpChannel.EMAIL).codeHash(hash("654321"))
                .expiresAt(LocalDateTime.now().plusMinutes(5)).attempts(0).build()));

        assertThat(service.verify(3L, OtpChannel.EMAIL, "654321")).isTrue();
        verify(userDao).markEmailVerified(3L);
        verify(userDao, never()).markPhoneVerified(any());
        verify(otpCodeDao).deleteByUserIdAndChannel(3L, OtpChannel.EMAIL);
    }

    @Test
    void verifyMarksPhoneVerifiedOnAMatch() throws Exception {
        when(otpCodeDao.findLatest(3L, OtpChannel.PHONE)).thenReturn(Optional.of(OtpCode.builder()
                .id(9L).userId(3L).channel(OtpChannel.PHONE).codeHash(hash("654321"))
                .expiresAt(LocalDateTime.now().plusMinutes(5)).attempts(0).build()));

        assertThat(service.verify(3L, OtpChannel.PHONE, "654321")).isTrue();
        verify(userDao).markPhoneVerified(3L);
        verify(userDao, never()).markEmailVerified(any());
        verify(otpCodeDao).deleteByUserIdAndChannel(3L, OtpChannel.PHONE);
    }

    // --- password reset ---

    @Test
    void issuePasswordResetOtpDoesNothingWhenSmtpIsNotConfigured() {
        when(emailService.isConfigured()).thenReturn(false);

        service.issuePasswordResetOtp(User.builder().id(1L).name("A").email("a@demo.local").build());

        verifyNoInteractions(otpCodeDao);
        verify(emailService, never()).sendPasswordResetEmail(anyString(), anyString(), anyString());
    }

    @Test
    void issuePasswordResetOtpSendsAResetEmailNotAVerificationEmail() {
        when(emailService.isConfigured()).thenReturn(true);
        when(otpCodeDao.save(any())).thenAnswer(inv -> inv.getArgument(0));
        User user = User.builder().id(7L).name("A Student").email("student@demo.local").build();

        service.issuePasswordResetOtp(user);

        ArgumentCaptor<OtpCode> captor = ArgumentCaptor.forClass(OtpCode.class);
        verify(otpCodeDao).save(captor.capture());
        assertThat(captor.getValue().getChannel()).isEqualTo(OtpChannel.PWRESET);

        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendPasswordResetEmail(eq("student@demo.local"), eq("A Student"), codeCaptor.capture());
        assertThat(codeCaptor.getValue()).matches("\\d{6}");
        // Wrong wording here would tell someone whose account is being probed to go ahead
        // and enter the code.
        verify(emailService, never()).sendOtpEmail(anyString(), anyString(), anyString());
    }

    @Test
    void issuingAResetCodeDoesNotWipeAPendingEmailVerificationCode() {
        when(emailService.isConfigured()).thenReturn(true);
        when(otpCodeDao.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.issuePasswordResetOtp(User.builder().id(7L).name("A").email("a@demo.local").build());

        // issue() clears prior codes per channel — the separate PWRESET channel is what
        // keeps a half-finished signup from being destroyed by a reset request.
        verify(otpCodeDao).deleteByUserIdAndChannel(7L, OtpChannel.PWRESET);
        verify(otpCodeDao, never()).deleteByUserIdAndChannel(7L, OtpChannel.EMAIL);
    }

    @Test
    void verifyPasswordResetReturnsFalseWhenNoCodeWasEverIssued() {
        when(otpCodeDao.findLatest(1L, OtpChannel.PWRESET)).thenReturn(Optional.empty());

        assertThat(service.verifyPasswordReset(1L, "123456")).isFalse();
    }

    @Test
    void verifyPasswordResetReturnsFalseForAnExpiredCode() throws Exception {
        when(otpCodeDao.findLatest(1L, OtpChannel.PWRESET)).thenReturn(Optional.of(OtpCode.builder()
                .id(5L).userId(1L).channel(OtpChannel.PWRESET).codeHash(hash("123456"))
                .expiresAt(LocalDateTime.now().minusMinutes(1)).build()));

        assertThat(service.verifyPasswordReset(1L, "123456")).isFalse();
    }

    @Test
    void verifyPasswordResetIncrementsAttemptsForAWrongCode() throws Exception {
        when(otpCodeDao.findLatest(1L, OtpChannel.PWRESET)).thenReturn(Optional.of(OtpCode.builder()
                .id(5L).userId(1L).channel(OtpChannel.PWRESET).codeHash(hash("123456"))
                .expiresAt(LocalDateTime.now().plusMinutes(5)).attempts(0).build()));

        assertThat(service.verifyPasswordReset(1L, "000000")).isFalse();
        verify(otpCodeDao).incrementAttempts(5L);
    }

    @Test
    void verifyPasswordResetStopsAfterTooManyWrongAttempts() throws Exception {
        when(otpCodeDao.findLatest(1L, OtpChannel.PWRESET)).thenReturn(Optional.of(OtpCode.builder()
                .id(5L).userId(1L).channel(OtpChannel.PWRESET).codeHash(hash("123456"))
                .expiresAt(LocalDateTime.now().plusMinutes(5)).attempts(5).build()));

        assertThat(service.verifyPasswordReset(1L, "123456")).isFalse();
        verify(otpCodeDao, never()).incrementAttempts(any());
    }

    @Test
    void verifyPasswordResetConsumesTheCodeButDoesNotMarkAnythingVerified() throws Exception {
        when(otpCodeDao.findLatest(3L, OtpChannel.PWRESET)).thenReturn(Optional.of(OtpCode.builder()
                .id(9L).userId(3L).channel(OtpChannel.PWRESET).codeHash(hash("654321"))
                .expiresAt(LocalDateTime.now().plusMinutes(5)).attempts(0).build()));

        assertThat(service.verifyPasswordReset(3L, "654321")).isTrue();
        // Consumed, so the same code cannot be replayed to set the password a second time.
        verify(otpCodeDao).deleteByUserIdAndChannel(3L, OtpChannel.PWRESET);
        // A reset must not double as email verification — that is verify()'s job, not this one.
        verify(userDao, never()).markEmailVerified(any());
        verify(userDao, never()).markPhoneVerified(any());
    }

    // --- sign-in second factor ---

    /**
     * The return value is what stops a second factor locking out the account that would
     * fix the relay. No code issued means no code to demand, and the caller lets the
     * sign-in through — see RoleBasedAuthenticationSuccessHandler.
     */
    @Test
    void issueLoginOtpReportsFailureWhenThereIsNoRelay() {
        when(emailService.isConfigured()).thenReturn(false);

        assertThat(service.issueLoginOtp(User.builder().id(1L).email("admin@demo.local").build())).isFalse();
        verifyNoInteractions(otpCodeDao);
    }

    @Test
    void issueLoginOtpReportsFailureWhenThereIsNoAddress() {
        when(emailService.isConfigured()).thenReturn(true);

        assertThat(service.issueLoginOtp(User.builder().id(1L).email("  ").build())).isFalse();
        verifyNoInteractions(otpCodeDao);
    }

    @Test
    void issueLoginOtpSendsACodeAndReportsSuccess() {
        when(emailService.isConfigured()).thenReturn(true);
        when(otpCodeDao.save(any())).thenAnswer(inv -> inv.getArgument(0));
        User admin = User.builder().id(3L).name("An Admin").email("admin@demo.local").build();

        assertThat(service.issueLoginOtp(admin)).isTrue();

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendLoginCodeEmail(eq("admin@demo.local"), eq("An Admin"), code.capture());
        assertThat(code.getValue()).matches("\\d{6}");
        verify(otpCodeDao).deleteByUserIdAndChannel(3L, OtpChannel.LOGIN2FA);
    }

    @Test
    void verifyLoginOtpConsumesTheCodeSoItCannotBeReplayed() throws Exception {
        when(otpCodeDao.findLatest(3L, OtpChannel.LOGIN2FA)).thenReturn(Optional.of(OtpCode.builder()
                .id(9L).userId(3L).channel(OtpChannel.LOGIN2FA).codeHash(hash("112233"))
                .expiresAt(LocalDateTime.now().plusMinutes(5)).attempts(0).build()));

        assertThat(service.verifyLoginOtp(3L, "112233")).isTrue();
        verify(otpCodeDao).deleteByUserIdAndChannel(3L, OtpChannel.LOGIN2FA);
        verify(userDao, never()).markEmailVerified(any());
    }
}
