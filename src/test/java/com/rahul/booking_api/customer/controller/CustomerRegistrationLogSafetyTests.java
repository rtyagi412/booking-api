package com.rahul.booking_api.customer.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.rahul.booking_api.TestcontainersConfiguration;
import com.rahul.booking_api.customer.repository.AccountRepository;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Log safety (spec §5, CLAUDE.md §7): the password, its hash and the email must not reach the logs on the
 * success, duplicate and validation paths.
 *
 * Observation limits: {@link OutputCaptureExtension} captures what this JVM writes to System.out/System.err
 * from the start of each test (it is reset per test), i.e. the console appender at the configured levels
 * (Boot default: INFO and above, no SQL or bind-parameter logging). It does not see DEBUG/TRACE output that is
 * disabled, files or other appenders, or the PostgreSQL container's own server log. Assertion messages name
 * the value's label only, never the value.
 */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class CustomerRegistrationLogSafetyTests {

	private static final String PATH = "/api/customers";
	private static final String CANARY = "log-capture-canary-5509";
	private static final String EMAIL = "zq-leak-7731@example.com";
	private static final String EMAIL_VARIANT = "  ZQ-Leak-7731@Example.COM ";
	private static final String PASSWORD = "Pw-LogProbe-8842!zx";
	private static final String OTHER_PASSWORD = "Pw-LogProbe-OTHER-3319#qv";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoSpyBean
	private AccountRepository accountRepository;

	@BeforeEach
	void cleanTable(CapturedOutput output) {
		accountRepository.deleteAll();
		LoggerFactory.getLogger(CustomerRegistrationLogSafetyTests.class).info(CANARY);
		assertThat(output.getAll().contains(CANARY)).as("log capture is active").isTrue();
	}

	@Test
	void successfulRegistrationLogsNeitherPasswordHashNorEmail(CapturedOutput output) throws Exception {
		String body = register("Asha", EMAIL, PASSWORD).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		Number id = JsonPath.read(body, "$.id");
		String hash = jdbcTemplate.queryForObject("select password_hash from accounts where id = ?",
				String.class, id.longValue());

		assertNotLogged(output, Map.of("password", PASSWORD, "password hash", hash, "email", EMAIL));
	}

	@Test
	void duplicateRejectedByLookupLogsNeitherPasswordsHashNorEmail(CapturedOutput output) throws Exception {
		register("Asha", EMAIL, PASSWORD).andExpect(status().isCreated());
		String hash = jdbcTemplate.queryForObject("select password_hash from accounts where email = ?",
				String.class, EMAIL);

		register("Other", EMAIL_VARIANT, OTHER_PASSWORD).andExpect(status().isConflict());

		assertNotLogged(output, Map.of("password", PASSWORD, "second password", OTHER_PASSWORD,
				"password hash", hash, "email", EMAIL));
	}

	@Test
	void duplicateRejectedByDatabaseConstraintLogsNeitherPasswordsHashNorEmail(CapturedOutput output)
			throws Exception {
		register("Asha", EMAIL, PASSWORD).andExpect(status().isCreated());
		String hash = jdbcTemplate.queryForObject("select password_hash from accounts where email = ?",
				String.class, EMAIL);
		doReturn(false).when(accountRepository).existsByEmail(anyString());

		register("Other", EMAIL_VARIANT, OTHER_PASSWORD).andExpect(status().isConflict());

		assertNotLogged(output, Map.of("password", PASSWORD, "second password", OTHER_PASSWORD,
				"password hash", hash, "email", EMAIL));
	}

	@Test
	void validationRejectionLogsNeitherPasswordNorEmail(CapturedOutput output) throws Exception {
		String badEmail = "ZQ-Leak-Invalid-7731";
		String longPassword = OTHER_PASSWORD + "x".repeat(80);

		register(" ", badEmail, longPassword).andExpect(status().isBadRequest());
		register("Asha", EMAIL, " ").andExpect(status().isBadRequest());

		assertNotLogged(output, Map.of("too long password", longPassword, "invalid email", badEmail,
				"valid email", EMAIL));
	}

	private void assertNotLogged(CapturedOutput output, Map<String, String> sensitiveValues) {
		String logs = output.getAll().toLowerCase();
		sensitiveValues.forEach((label, value) -> assertThat(logs.contains(value.trim().toLowerCase()))
				.as("logs contain the %s", label).isFalse());
	}

	private ResultActions register(String name, String email, String password) throws Exception {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("name", name);
		fields.put("email", email);
		fields.put("password", password);
		StringBuilder json = new StringBuilder("{");
		fields.forEach((key, value) -> json.append(json.length() > 1 ? "," : "")
				.append('"').append(key).append("\":\"").append(value).append('"'));
		return mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
				.content(json.append('}').toString().getBytes(StandardCharsets.UTF_8)));
	}

}
