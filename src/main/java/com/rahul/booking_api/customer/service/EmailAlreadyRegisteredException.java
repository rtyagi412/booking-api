package com.rahul.booking_api.customer.service;

public class EmailAlreadyRegisteredException extends RuntimeException {

	public static final String MESSAGE = "email is already registered";

	public EmailAlreadyRegisteredException() {
		super(MESSAGE);
	}

}
