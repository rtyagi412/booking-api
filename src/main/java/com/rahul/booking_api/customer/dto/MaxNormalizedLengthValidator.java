package com.rahul.booking_api.customer.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Locale;

public class MaxNormalizedLengthValidator implements ConstraintValidator<MaxNormalizedLength, String> {

	private int max;

	@Override
	public void initialize(MaxNormalizedLength constraint) {
		this.max = constraint.value();
	}

	@Override
	public boolean isValid(String value, ConstraintValidatorContext context) {
		return value == null || value.trim().toLowerCase(Locale.ROOT).length() <= max;
	}

}
