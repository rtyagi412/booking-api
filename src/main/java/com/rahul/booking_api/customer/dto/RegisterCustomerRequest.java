package com.rahul.booking_api.customer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterCustomerRequest(
		@NotBlank(message = "must not be blank")
		@Size(max = 32, message = "must be at most 32 characters")
		String name,

		@NotBlank(message = "must not be blank")
		@Size(max = 32, message = "must be at most 32 characters")
		@Email(message = "must be a valid email address")
		String email,

		@NotBlank(message = "must not be blank")
		@MaxUtf8Bytes(value = 72, message = "must be at most 72 bytes")
		String password) {

	public RegisterCustomerRequest {
		name = name == null ? null : name.trim();
		email = email == null ? null : email.trim();
	}

	@Override
	public String toString() {
		return "RegisterCustomerRequest[name=" + name + ", email=" + email + "]";
	}

}
