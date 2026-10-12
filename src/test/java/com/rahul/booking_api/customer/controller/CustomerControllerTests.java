package com.rahul.booking_api.customer.controller;

import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.rahul.booking_api.TestcontainersConfiguration;
import com.rahul.booking_api.customer.entity.Account;
import com.rahul.booking_api.customer.entity.Role;
import com.rahul.booking_api.customer.repository.AccountRepository;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CustomerControllerTests {

	private static final String PATH = "/api/customers";
	private static final String BLANK = "must not be blank";
	private static final String TOO_LONG = "must be at most 32 characters";
	private static final String BAD_EMAIL = "must be a valid email address";
	private static final String PASSWORD_TOO_LONG = "must be at most 72 bytes";
	private static final String DUPLICATE = "email is already registered";
	private static final String UNAUTHORIZED_BODY =
			"{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"authentication required\"}";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@BeforeEach
	void cleanTable() {
		accountRepository.deleteAll();
	}

	// ---- success, response shape (T1, T2) ----

	@Test
	void anonymousRegistrationReturns201WithExactlyFourFields() throws Exception {
		String password = "TopSecret-123";

		MockHttpServletResponse response = register("Asha Rao", "Asha@Example.com", password)
				.andExpect(status().isCreated())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.name").value("Asha Rao"))
				.andExpect(jsonPath("$.email").value("asha@example.com"))
				.andExpect(jsonPath("$.role").value("CUSTOMER"))
				.andReturn().getResponse();

		String body = response.getContentAsString();
		Map<String, Object> json = JsonPath.read(body, "$");
		assertThat(json.keySet()).containsExactlyInAnyOrder("id", "name", "email", "role");
		assertThat(body).doesNotContain(password).doesNotContain("{bcrypt}").doesNotContain("$2");
		assertThat(accountRepository.existsByEmail("asha@example.com")).isTrue();
		assertThat(accountRepository.count()).isEqualTo(1);
	}

	// ---- persisted password (T13, AC 9) ----

	@Test
	void registrationPersistsOnlyABcryptHashOfThePasswordInTheDatabaseRow() throws Exception {
		String password = "Pw-7Qz!x9Lk-unique-T13";

		String body = register("Asha Rao", "Asha@Example.com", password)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.name").value("Asha Rao"))
				.andExpect(jsonPath("$.email").value("asha@example.com"))
				.andExpect(jsonPath("$.role").value("CUSTOMER"))
				.andReturn().getResponse().getContentAsString();
		Map<String, Object> json = JsonPath.read(body, "$");
		assertThat(json.keySet()).containsExactlyInAnyOrder("id", "name", "email", "role");
		Number id = JsonPath.read(body, "$.id");

		Map<String, Object> row = jdbcTemplate.queryForMap("select * from accounts where id = ?", id.longValue());

		assertThat(row.keySet()).containsExactlyInAnyOrder("id", "name", "email", "password_hash", "role");
		assertThat(row.get("name")).isEqualTo("Asha Rao");
		assertThat(row.get("email")).isEqualTo("asha@example.com");
		assertThat(row.get("role")).isEqualTo("CUSTOMER");
		String hash = (String) row.get("password_hash");
		assertThat(hash != null && hash.startsWith("{bcrypt}")).as("password_hash starts with {bcrypt}").isTrue();
		assertThat(hash.equals(password)).as("password_hash differs from the supplied password").isFalse();
		assertThat(passwordEncoder.matches(password, hash)).as("password_hash matches the supplied password").isTrue();
		row.forEach((column, value) -> assertThat(value != null && String.valueOf(value).contains(password))
				.as("column '%s' contains the plain password", column).isFalse());
	}

	// ---- role ignored (T3) ----

	@ParameterizedTest
	@ValueSource(strings = {"ADMIN", "MANAGER", "BOGUS"})
	void suppliedRoleIsIgnored(String role) throws Exception {
		String json = "{\"name\":\"Asha\",\"email\":\"asha@example.com\",\"password\":\"x\",\"role\":\"" + role + "\"}";

		postJson(json).andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("CUSTOMER"));

		assertThat(accountRepository.findAll()).singleElement()
				.extracting(Account::getRole).isEqualTo(Role.CUSTOMER);
	}

	// ---- duplicates (T4-T7, T17) ----

	@ParameterizedTest
	@ValueSource(strings = {"asha@example.com", "ASHA@Example.COM", "  asha@example.com  "})
	void duplicateEmailVariantsReturn409(String variant) throws Exception {
		register("Asha", "asha@example.com", "x").andExpect(status().isCreated());

		register("Other", variant, "y")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.error").value("Conflict"))
				.andExpect(jsonPath("$.message").value(DUPLICATE))
				.andExpect(jsonPath("$.fieldErrors").doesNotExist());
		assertThat(accountRepository.count()).isEqualTo(1);
	}

	@ParameterizedTest
	@EnumSource(value = Role.class, names = {"ADMIN", "MANAGER"})
	void emailOfSeededStaffAccountReturns409(Role role) throws Exception {
		accountRepository.saveAndFlush(Account.builder().name("Seeded").email("staff@example.com")
				.passwordHash("{bcrypt}hash").role(role).build());

		register("Asha", "  STAFF@Example.com ", "x")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(DUPLICATE));
		assertThat(accountRepository.count()).isEqualTo(1);
	}

	@Test
	void duplicateReportsDifferentStatusAndMessageFromInvalidInput() throws Exception {
		register("Asha", "asha@example.com", "x").andExpect(status().isCreated());

		String duplicateMessage = JsonPath.read(register("A", "asha@example.com", "x")
				.andExpect(status().isConflict()).andReturn().getResponse().getContentAsString(), "$.message");
		String invalidMessage = JsonPath.read(register("A", "bad", "x")
				.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString(), "$.message");

		assertThat(duplicateMessage).isEqualTo(DUPLICATE).isNotEqualTo(invalidMessage);
		assertThat(invalidMessage).isEqualTo("invalid request");
	}

	@Test
	void invalidAndDuplicateRequestReturns400() throws Exception {
		register("Asha", "asha@example.com", "x").andExpect(status().isCreated());

		register("", "asha@example.com", "x")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("invalid request"));
		assertThat(accountRepository.count()).isEqualTo(1);
	}

	@Test
	void sameNameWithDifferentEmailsReturns201Twice() throws Exception {
		register("Asha", "one@example.com", "x").andExpect(status().isCreated());
		register("Asha", "two@example.com", "y").andExpect(status().isCreated());

		assertThat(accountRepository.count()).isEqualTo(2);
	}

	// ---- name / email validation (T8, T9, T10, T14, T23) ----

	@Test
	void missingNameOrEmailOrPasswordReturns400WithThatField() throws Exception {
		for (String field : List.of("name", "email", "password")) {
			Map<String, Object> fields = validFields();
			fields.remove(field);

			postJson(json(fields))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.fieldErrors.length()").value(1))
					.andExpect(jsonPath("$.fieldErrors[0].field").value(field))
					.andExpect(jsonPath("$.fieldErrors[0].message").value(BLANK));
		}
		assertThat(accountRepository.count()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = {"name", "email", "password"})
	void nullEmptyAndWhitespaceValuesReturn400WithThatField(String field) throws Exception {
		for (String value : new String[] {null, "", "   ", "\t\n"}) {
			Map<String, Object> fields = validFields();
			fields.put(field, value);

			postJson(json(fields))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.fieldErrors.length()").value(1))
					.andExpect(jsonPath("$.fieldErrors[0].field").value(field))
					.andExpect(jsonPath("$.fieldErrors[0].message").value(BLANK));
		}
		assertThat(accountRepository.count()).isZero();
	}

	@Test
	void nameBoundaryIs32CharactersAfterTrimming() throws Exception {
		register("n".repeat(33), "a@example.com", "x")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value(TOO_LONG));
		register("n".repeat(32), "a@example.com", "x").andExpect(status().isCreated());
		register("  " + "m".repeat(32) + "  ", "b@example.com", "x")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("m".repeat(32)));
	}

	@Test
	void emailBoundaryIs32CharactersAfterTrimming() throws Exception {
		String email32 = "a".repeat(26) + "@b.com";

		register("Asha", "a" + email32, "x")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors[0].field").value("email"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value(TOO_LONG));
		register("Asha", email32, "x").andExpect(status().isCreated());
		register("Asha", "  " + "c".repeat(26) + "@b.com  ", "x").andExpect(status().isCreated());
	}

	@ParameterizedTest
	@ValueSource(strings = {"plain", "a@", "@b.com", "a b@c.com"})
	void invalidEmailFormatReturns400(String email) throws Exception {
		register("Asha", email, "x")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors.length()").value(1))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("email"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value(BAD_EMAIL));
	}

	@Test
	void fieldErrorsAreSortedByFieldThenMessageAndCarryNoRejectedValues() throws Exception {
		String badEmail = "x".repeat(40);

		String body = register("", badEmail, "").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.error").value("Bad Request"))
				.andExpect(jsonPath("$.message").value("invalid request"))
				.andExpect(jsonPath("$.fieldErrors.length()").value(4))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("email"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value(BAD_EMAIL))
				.andExpect(jsonPath("$.fieldErrors[1].field").value("email"))
				.andExpect(jsonPath("$.fieldErrors[1].message").value(TOO_LONG))
				.andExpect(jsonPath("$.fieldErrors[2].field").value("name"))
				.andExpect(jsonPath("$.fieldErrors[2].message").value(BLANK))
				.andExpect(jsonPath("$.fieldErrors[3].field").value("password"))
				.andExpect(jsonPath("$.fieldErrors[3].message").value(BLANK))
				.andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain(badEmail);
	}

	@Test
	void everyFieldErrorMessageIsOneOfTheSpecifiedStrings() throws Exception {
		String body = register("n".repeat(40), "x".repeat(40), "a".repeat(80))
				.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

		List<String> messages = JsonPath.read(body, "$.fieldErrors[*].message");
		assertThat(messages).isNotEmpty()
				.allMatch(m -> List.of(BLANK, TOO_LONG, BAD_EMAIL, PASSWORD_TOO_LONG).contains(m));
	}

	// ---- password (T11, T21) ----

	@Test
	void passwordWithSurroundingSpacesIsAccepted() throws Exception {
		register("Asha", "asha@example.com", " abc ").andExpect(status().isCreated());
	}

	@Test
	void asciiPasswordBoundaryIs72Bytes() throws Exception {
		register("Asha", "a@example.com", "a".repeat(72)).andExpect(status().isCreated());

		String password73 = "a".repeat(73);
		String body = register("Asha", "b@example.com", password73)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors.length()").value(1))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("password"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value(PASSWORD_TOO_LONG))
				.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain(password73);
		assertThat(accountRepository.count()).isEqualTo(1);
	}

	@Test
	void multiByteCharacterPasswordBoundaryIs72Bytes() throws Exception {
		register("Asha", "a@example.com", "é".repeat(36)).andExpect(status().isCreated());

		register("Asha", "b@example.com", "é".repeat(37))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors[0].field").value("password"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value(PASSWORD_TOO_LONG));
		assertThat(accountRepository.count()).isEqualTo(1);
	}

	// ---- malformed requests, content type, method (T18) ----

	@ParameterizedTest
	@ValueSource(strings = {"{not json", "[]", "\"text\"", "{\"name\":{\"a\":1},\"email\":\"a@b.com\",\"password\":\"x\"}"})
	void malformedJsonReturns400WithoutParserDetail(String malformed) throws Exception {
		String body = postJson(malformed)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.error").value("Bad Request"))
				.andExpect(jsonPath("$.message").value("malformed request body"))
				.andExpect(jsonPath("$.fieldErrors").doesNotExist())
				.andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain("Jackson").doesNotContain("Unexpected").doesNotContain("line:");
	}

	@Test
	void emptyBodyReturns400() throws Exception {
		mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("malformed request body"));
	}

	@Test
	void missingContentTypeReturns415() throws Exception {
		mockMvc.perform(post(PATH).content("{}")).andExpect(status().isUnsupportedMediaType());
	}

	@Test
	void plainTextContentTypeReturns415NotUnauthorized() throws Exception {
		mockMvc.perform(post(PATH).contentType(MediaType.TEXT_PLAIN).content("hello"))
				.andExpect(status().isUnsupportedMediaType());
	}

	@Test
	void anonymousGetAndPutOnCustomersReturnTheSpecified401() throws Exception {
		for (var request : List.of(get(PATH), put(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))) {
			mockMvc.perform(request)
					.andExpect(status().isUnauthorized())
					.andExpect(content().string(UNAUTHORIZED_BODY))
					.andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
		}
	}

	// ---- no login (T16) ----

	@Test
	void registrationIssuesNoSessionCookieOrTokenAndLeavesFollowUpRequestsAnonymous() throws Exception {
		var result = register("Asha", "asha@example.com", "x").andExpect(status().isCreated()).andReturn();
		MockHttpServletResponse response = result.getResponse();

		assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
		assertThat(response.getHeader(HttpHeaders.AUTHORIZATION)).isNull();
		assertThat(response.getHeader(HttpHeaders.LOCATION)).isNull();
		assertThat(response.getCookies()).isEmpty();
		assertThat(result.getRequest().getSession(false)).isNull();
		assertThat(response.getContentAsString()).doesNotContainIgnoringCase("token")
				.doesNotContain("JSESSIONID");

		mockMvc.perform(get("/anything"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().string(UNAUTHORIZED_BODY));
		mockMvc.perform(get(PATH))
				.andExpect(status().isUnauthorized())
				.andExpect(content().string(UNAUTHORIZED_BODY));
	}

	// ---- helpers ----

	private ResultActions register(String name, String email, String password) throws Exception {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("name", name);
		fields.put("email", email);
		fields.put("password", password);
		return postJson(json(fields));
	}

	private ResultActions postJson(String json) throws Exception {
		return mockMvc.perform(post(PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json.getBytes(StandardCharsets.UTF_8)));
	}

	private static Map<String, Object> validFields() {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("name", "Asha");
		fields.put("email", "asha@example.com");
		fields.put("password", "s3cret");
		return fields;
	}

	private static String json(Map<String, Object> fields) {
		StringBuilder builder = new StringBuilder("{");
		fields.forEach((key, value) -> {
			if (builder.length() > 1) {
				builder.append(',');
			}
			builder.append('"').append(key).append("\":").append(value == null ? "null" : quote(value.toString()));
		});
		return builder.append('}').toString();
	}

	private static String quote(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
				.replace("\t", "\\t") + "\"";
	}

}
