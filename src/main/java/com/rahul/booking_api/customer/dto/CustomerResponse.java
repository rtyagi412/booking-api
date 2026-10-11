package com.rahul.booking_api.customer.dto;

import com.rahul.booking_api.customer.entity.Account;
import com.rahul.booking_api.customer.entity.Role;

public record CustomerResponse(Long id, String name, String email, Role role) {

	public static CustomerResponse from(Account account) {
		return new CustomerResponse(account.getId(), account.getName(), account.getEmail(), account.getRole());
	}

}
