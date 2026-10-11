package com.rahul.booking_api;

import org.springframework.boot.SpringApplication;

public class TestBookingApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(BookingApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
