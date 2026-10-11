package com.rahul.booking_api.customer.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class RegisterCustomerRequestTests {

	private static final String BLANK = "must not be blank";
	private static final String TOO_LONG = "must be at most 32 characters";
	private static final String BAD_EMAIL = "must be a valid email address";
	private static final String PASSWORD_TOO_LONG = "must be at most 72 bytes";
	private static final String VALID_EMAIL = "asha@example.com";

	private static ValidatorFactory factory;
	private static Validator validator;

	@BeforeAll
	static void createValidator() {
		factory = Validation.buildDefaultValidatorFactory();
		validator = factory.getValidator();
	}

	@AfterAll
	static void closeFactory() {
		factory.close();
	}

	@Test
	void validRequestHasNoViolations() {
		assertThat(violations(new RegisterCustomerRequest("Asha", VALID_EMAIL, "s3cret"))).isEmpty();
	}

	@Test
	void trimsNameAndEmailButNotPassword() {
		RegisterCustomerRequest request = new RegisterCustomerRequest("  Asha Rao ", " " + VALID_EMAIL + "\t", " abc ");

		assertThat(request.name()).isEqualTo("Asha Rao");
		assertThat(request.email()).isEqualTo(VALID_EMAIL);
		assertThat(request.password()).isEqualTo(" abc ");
	}

	@Test
	void doesNotLowerCaseEmail() {
		assertThat(new RegisterCustomerRequest("Asha", "Asha@Example.COM", "x").email()).isEqualTo("Asha@Example.COM");
	}

	@Test
	void nullNameAndEmailStayNull() {
		RegisterCustomerRequest request = new RegisterCustomerRequest(null, null, "x");

		assertThat(request.name()).isNull();
		assertThat(request.email()).isNull();
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", "   ", "\t\n"})
	void blankOrMissingNameIsRejected(String name) {
		assertThat(violations(new RegisterCustomerRequest(name, VALID_EMAIL, "x")))
				.containsExactly("name: " + BLANK);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", "   ", "\t\n"})
	void blankOrMissingEmailIsRejected(String email) {
		assertThat(violations(new RegisterCustomerRequest("Asha", email, "x")))
				.containsExactly("email: " + BLANK);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", "   ", "\t\n"})
	void blankOrMissingPasswordIsRejected(String password) {
		assertThat(violations(new RegisterCustomerRequest("Asha", VALID_EMAIL, password)))
				.containsExactly("password: " + BLANK);
	}

	@Test
	void nameOf32CharactersIsAcceptedAnd33Rejected() {
		assertThat(violations(new RegisterCustomerRequest("n".repeat(32), VALID_EMAIL, "x"))).isEmpty();
		assertThat(violations(new RegisterCustomerRequest("n".repeat(33), VALID_EMAIL, "x")))
				.containsExactly("name: " + TOO_LONG);
	}

	@Test
	void emailOf32CharactersIsAcceptedAnd33Rejected() {
		String email32 = "a".repeat(32 - "@b.com".length()) + "@b.com";
		String email33 = "a" + email32;

		assertThat(violations(new RegisterCustomerRequest("Asha", email32, "x"))).isEmpty();
		assertThat(violations(new RegisterCustomerRequest("Asha", email33, "x")))
				.containsExactly("email: " + TOO_LONG);
	}

	@Test
	void lengthIsMeasuredAfterTrimming() {
		String paddedName = "  " + "n".repeat(32) + "  ";
		String paddedEmail = " " + "a".repeat(32 - "@b.com".length()) + "@b.com ";

		assertThat(violations(new RegisterCustomerRequest(paddedName, paddedEmail, "x"))).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {"plain", "a@", "@b.com", "a b@c.com"})
	void invalidEmailFormatIsRejected(String email) {
		assertThat(violations(new RegisterCustomerRequest("Asha", email, "x")))
				.containsExactly("email: " + BAD_EMAIL);
	}

	@Test
	void longMalformedEmailReportsBothMessages() {
		String email = "x".repeat(40);

		assertThat(violations(new RegisterCustomerRequest("Asha", email, "x")))
				.containsExactlyInAnyOrder("email: " + TOO_LONG, "email: " + BAD_EMAIL);
	}

	@Test
	void whitespaceAroundPasswordIsPreservedAndAccepted() {
		RegisterCustomerRequest request = new RegisterCustomerRequest("Asha", VALID_EMAIL, " abc ");

		assertThat(violations(request)).isEmpty();
		assertThat(request.password()).isEqualTo(" abc ");
	}

	@Test
	void asciiPasswordBoundaryIs72Bytes() {
		assertThat(violations(new RegisterCustomerRequest("Asha", VALID_EMAIL, "a".repeat(72)))).isEmpty();
		assertThat(violations(new RegisterCustomerRequest("Asha", VALID_EMAIL, "a".repeat(73))))
				.containsExactly("password: " + PASSWORD_TOO_LONG);
	}

	@Test
	void multiByteCharacterPasswordBoundaryIs72Bytes() {
		assertThat(violations(new RegisterCustomerRequest("Asha", VALID_EMAIL, "é".repeat(36)))).isEmpty();
		assertThat(violations(new RegisterCustomerRequest("Asha", VALID_EMAIL, "é".repeat(37))))
				.containsExactly("password: " + PASSWORD_TOO_LONG);
	}

	@Test
	void multipleInvalidFieldsAreAllReported() {
		assertThat(violations(new RegisterCustomerRequest(" ", "bad", "")))
				.containsExactlyInAnyOrder("name: " + BLANK, "email: " + BAD_EMAIL, "password: " + BLANK);
	}

	@Test
	void everyMessageIsOneOfTheSpecifiedStrings() {
		List<String> allowed = List.of(BLANK, TOO_LONG, BAD_EMAIL, PASSWORD_TOO_LONG);
		Set<ConstraintViolation<RegisterCustomerRequest>> found = validator.validate(
				new RegisterCustomerRequest("n".repeat(40), "x".repeat(40), "a".repeat(80)));

		assertThat(found).extracting(ConstraintViolation::getMessage).isNotEmpty().allMatch(allowed::contains);
	}

	@Test
	void toStringOmitsPassword() {
		String text = new RegisterCustomerRequest("Asha", VALID_EMAIL, "TopSecret-123").toString();

		assertThat(text).doesNotContain("TopSecret-123").doesNotContain("password");
		assertThat(text).contains("Asha").contains(VALID_EMAIL);
	}

	private static Set<String> violations(RegisterCustomerRequest request) {
		return validator.validate(request).stream()
				.map(v -> v.getPropertyPath() + ": " + v.getMessage())
				.collect(Collectors.toSet());
	}

}
