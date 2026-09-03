package com.bitesite.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;

/**
 * The one definition of what counts as an acceptable password for a self-chosen
 * credential: at least 8 characters, with at least one letter and one number.
 *
 * <p>These rules used to live inline on {@link StudentRegistrationForm} alone. Once
 * changing and resetting a password became possible there were three forms that all had
 * to agree, and three copies of a rule is how they stop agreeing — a tightened
 * registration rule that a reset form quietly undoes leaves the account weaker than the
 * screen that created it claimed. Composing the constraints here means there is one place
 * to change.
 *
 * <p>Deliberately not applied to the admin-facing forms that provision an account for
 * someone else ({@code PlatformUserForm}, the staff-creation form): those set a temporary
 * credential the account holder is expected to replace, and are already gated behind an
 * authenticated admin.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({FIELD, ANNOTATION_TYPE})
@Constraint(validatedBy = {})
@NotBlank(message = "Password is required")
@Size(min = 8, message = "Password must be at least 8 characters")
@Pattern(regexp = ".*[A-Za-z].*", message = "Password must include at least one letter")
@Pattern(regexp = ".*\\d.*", message = "Password must include at least one number")
public @interface StrongPassword {

    String message() default "Password is not strong enough";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
