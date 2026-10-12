package com.rahul.booking_api.customer.controller;

import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.rahul.booking_api.TestcontainersConfiguration;
import com.rahul.booking_api.customer.dto.RegisterCustomerRequest;
import com.rahul.booking_api.customer.repository.AccountRepository;
import jakarta.validation.Validator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Regression for REVIEW.md Q1: the 32-character email limit also applies after normalisation.
 * U+0130 (capital I with dot above) lower-cases to two chars ("i" + U+0307) under Locale.ROOT.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class CustomerRegistrationNormalizedEmailLengthTests {

	private static final int LIMIT = 32;
	// 20 x U+0130 + "@example.com" = 32 chars as submitted, 52 chars after lower-casing.
	private static final String EMAIL = "İ".repeat(20) + "@example.com";

	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	@LocalServerPort
	private int port;

	@Autowired
	private Validator validator;

	@Autowired
	private AccountRepository accountRepository;

	@BeforeEach
	void cleanTable() {
		accountRepository.deleteAll();
	}

	@Test
	void candidateEmailIsWithinTheLimitAsSubmittedButNotAfterNormalisation() {
		assertThat(EMAIL).hasSize(LIMIT);
		assertThat(EMAIL.trim().toLowerCase(Locale.ROOT)).hasSize(52);
	}

	@Test
	void registrationOfEmailThatExceedsTheLimitAfterNormalisationIsRejectedWith400AndStoresNothing() throws Exception {
		HttpResponse<String> response = register("Asha", EMAIL);

		assertThat(response.statusCode()).as("response body: %s", response.body()).isEqualTo(400);
		assertThat((String) JsonPath.read(response.body(), "$.message")).isEqualTo("invalid request");
		List<Map<String, String>> fieldErrors = JsonPath.read(response.body(), "$.fieldErrors");
		assertThat(fieldErrors).containsExactly(Map.of("field", "email", "message", "must be at most 32 characters"));
		assertThat(accountRepository.count()).isZero();
	}

	@Test
	void emailExceedingTheLimitByExactlyOneCharacterAfterNormalisationIsRejectedWith400AndStoresNothing()
			throws Exception {
		// 10 x U+0130 + "a" + "@example.com" = 23 chars as submitted, 20 + 1 + 12 = 33 after lower-casing.
		String justOver = "İ".repeat(10) + "a@example.com";

		assertThat(justOver).hasSize(23);
		assertThat(justOver.trim().toLowerCase(Locale.ROOT)).hasSize(LIMIT + 1);

		HttpResponse<String> response = register("Asha", justOver);

		assertThat(response.statusCode()).as("response body: %s", response.body()).isEqualTo(400);
		List<Map<String, String>> fieldErrors = JsonPath.read(response.body(), "$.fieldErrors");
		assertThat(fieldErrors).containsExactly(Map.of("field", "email", "message", "must be at most 32 characters"));
		assertThat(accountRepository.count()).isZero();
	}

	@Test
	void emailWithinTheLimitAfterNormalisationStillRegistersAndDuplicatesStillConflict() throws Exception {
		String fits = "İ".repeat(10) + "@example.com"; // 22 chars, 32 after lower-casing

		assertThat(validator.validate(new RegisterCustomerRequest("Asha", fits, "s3cret"))).isEmpty();
		assertThat(register("Asha", fits).statusCode()).isEqualTo(201);
		assertThat(accountRepository.count()).isEqualTo(1);

		HttpResponse<String> duplicate = register("Asha", fits);
		assertThat(duplicate.statusCode()).isEqualTo(409);
		assertThat((String) JsonPath.read(duplicate.body(), "$.message")).isEqualTo("email is already registered");
		assertThat(accountRepository.count()).isEqualTo(1);
	}

	private HttpResponse<String> register(String name, String email) throws Exception {
		String body = "{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"password\":\"s3cret\"}";
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/customers"))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json; charset=UTF-8")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
		return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
	}

}
