package com.bitesite.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SmtpEmailServiceTest {

    @Mock private JavaMailSender mailSender;

    @Test
    void isNotConfiguredWhenHostIsBlank() {
        SmtpEmailService service = new SmtpEmailService(mailSender, "", "no-reply@bitesite.local");

        assertThat(service.isConfigured()).isFalse();
    }

    @Test
    void isConfiguredWhenHostIsSet() {
        SmtpEmailService service = new SmtpEmailService(mailSender, "smtp.example.com", "no-reply@bitesite.local");

        assertThat(service.isConfigured()).isTrue();
    }

    @Test
    void sendOtpEmailSkipsSendingWhenNotConfigured() {
        SmtpEmailService service = new SmtpEmailService(mailSender, "", "no-reply@bitesite.local");

        service.sendOtpEmail("student@demo.local", "A Student", "123456");

        verifyNoInteractions(mailSender);
    }

    @Test
    void sendPasswordResetEmailSkipsSendingWhenNotConfigured() {
        SmtpEmailService service = new SmtpEmailService(mailSender, "", "no-reply@bitesite.local");

        service.sendPasswordResetEmail("student@demo.local", "A Student", "123456");

        verifyNoInteractions(mailSender);
    }
}
