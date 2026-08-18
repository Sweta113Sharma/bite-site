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
}
