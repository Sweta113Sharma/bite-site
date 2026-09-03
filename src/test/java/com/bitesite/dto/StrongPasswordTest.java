package com.bitesite.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A composed constraint that is wired up wrongly does not fail loudly — it simply
 * validates nothing, and every password form silently starts accepting "a". These assert
 * the rules actually fire, and that they still say what the registration form said before
 * the rules moved behind {@link StrongPassword}.
 */
class StrongPasswordTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private Set<String> messagesFor(String password) {
        ChangePasswordForm form = new ChangePasswordForm();
        form.setCurrentPassword("whatever");
        form.setNewPassword(password);
        form.setConfirmPassword(password);
        return validator.validate(form).stream()
                .filter(v -> v.getPropertyPath().toString().equals("newPassword"))
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    @Test
    void acceptsAPasswordWithLettersNumbersAndEnoughLength() {
        assertThat(messagesFor("lunch2024")).isEmpty();
    }

    @Test
    void rejectsAShortPassword() {
        assertThat(messagesFor("a1b2")).contains("Password must be at least 8 characters");
    }

    @Test
    void rejectsAPasswordWithNoDigit() {
        assertThat(messagesFor("passwordonly")).contains("Password must include at least one number");
    }

    @Test
    void rejectsAPasswordWithNoLetter() {
        assertThat(messagesFor("12345678")).contains("Password must include at least one letter");
    }

    @Test
    void rejectsABlankPassword() {
        assertThat(messagesFor("")).contains("Password is required");
    }

    @Test
    void appliesTheSameRulesToRegistration() {
        StudentRegistrationForm form = new StudentRegistrationForm();
        form.setTenantId(1L);
        form.setName("A Student");
        form.setEmail("student@demo.local");
        form.setPassword("short");

        Set<String> messages = validator.validate(form).stream()
                .filter(v -> v.getPropertyPath().toString().equals("password"))
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());

        assertThat(messages).containsExactlyInAnyOrder(
                "Password must be at least 8 characters",
                "Password must include at least one number");
    }
}
