package com.rahul.booking_api.customer.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The value, trimmed and lower-cased with {@code Locale.ROOT} (the stored email form), has at most
 * {@code value} characters. Replaces {@code @Size} for email: lower-casing can lengthen a string.
 */
@Documented
@Constraint(validatedBy = MaxNormalizedLengthValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxNormalizedLength {

	int value();

	String message() default "must be at most {value} characters";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

}
