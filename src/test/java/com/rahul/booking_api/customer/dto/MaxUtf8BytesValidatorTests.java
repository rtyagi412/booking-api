package com.rahul.booking_api.customer.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MaxUtf8BytesValidatorTests {

	private static final String TWO_BYTE_CHAR = "é";

	private MaxUtf8BytesValidator validator;

	@BeforeEach
	void setUp() throws NoSuchFieldException {
		validator = new MaxUtf8BytesValidator();
		validator.initialize(Holder.class.getDeclaredField("value").getAnnotation(MaxUtf8Bytes.class));
	}

	@Test
	void nullIsValid() {
		assertThat(validator.isValid(null, null)).isTrue();
	}

	@Test
	void emptyIsValid() {
		assertThat(validator.isValid("", null)).isTrue();
	}

	@Test
	void acceptsExactly72AsciiBytes() {
		assertThat(validator.isValid("a".repeat(72), null)).isTrue();
	}

	@Test
	void rejects73AsciiBytes() {
		assertThat(validator.isValid("a".repeat(73), null)).isFalse();
	}

	@Test
	void accepts36TwoByteCharacters() {
		assertThat(validator.isValid(TWO_BYTE_CHAR.repeat(36), null)).isTrue();
	}

	@Test
	void rejects37TwoByteCharactersEvenThoughFewerThan72Characters() {
		assertThat(validator.isValid(TWO_BYTE_CHAR.repeat(37), null)).isFalse();
	}

	private static class Holder {

		@MaxUtf8Bytes(72)
		private String value;

	}

}
