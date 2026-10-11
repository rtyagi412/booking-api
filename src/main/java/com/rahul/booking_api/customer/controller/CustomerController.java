package com.rahul.booking_api.customer.controller;

import com.rahul.booking_api.customer.dto.CustomerResponse;
import com.rahul.booking_api.customer.dto.RegisterCustomerRequest;
import com.rahul.booking_api.customer.service.CustomerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

	private final CustomerService customerService;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public CustomerResponse register(@Valid @RequestBody RegisterCustomerRequest request) {
		return customerService.register(request);
	}

}
