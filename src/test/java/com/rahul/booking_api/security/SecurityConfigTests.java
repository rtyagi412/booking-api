package com.rahul.booking_api.security;

import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.rahul.booking_api.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityConfigTests {

	private static final String UNAUTHORIZED_BODY =
			"{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"authentication required\"}";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@ParameterizedTest
	@ValueSource(strings = {"/anything", "/api/customers", "/api/customers/1"})
	void anonymousGetIsUnauthorizedWithExactJsonBody(String path) throws Exception {
		assertUnauthorized(mockMvc.perform(get(path)).andReturn().getResponse());
	}

	@Test
	void anonymousPutOnCustomersIsUnauthorized() throws Exception {
		MockHttpServletRequestBuilder request = put("/api/customers")
				.contentType(MediaType.APPLICATION_JSON).content("{}");

		assertUnauthorized(mockMvc.perform(request).andReturn().getResponse());
	}

	@Test
	void unauthorizedResponseIssuesNoSessionAndNoChallenge() throws Exception {
		MockHttpServletResponse response = mockMvc.perform(get("/anything")).andReturn().getResponse();

		assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
		assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
		assertThat(response.getCookies()).isEmpty();
	}

	@Test
	void unauthorizedResponseCreatesNoHttpSession() throws Exception {
		assertThat(mockMvc.perform(get("/anything")).andReturn().getRequest().getSession(false)).isNull();
	}

	@Test
	void passwordEncoderAccepts72Bytes() {
		String password = "a".repeat(72);

		assertThat(passwordEncoder.matches(password, passwordEncoder.encode(password))).isTrue();
	}

	@Test
	void passwordEncoderRejects73Bytes() {
		assertThatThrownBy(() -> passwordEncoder.encode("a".repeat(73)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void passwordEncoderRejects74BytesFrom37TwoByteCharacters() {
		assertThatThrownBy(() -> passwordEncoder.encode("é".repeat(37)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void passwordEncoderAccepts72BytesFrom36TwoByteCharacters() {
		assertThat(passwordEncoder.encode("é".repeat(36))).isNotBlank();
	}

	@Test
	void hashesUseBcryptPrefixAndDoNotContainThePassword() {
		String hash = passwordEncoder.encode("s3cret");

		assertThat(hash).startsWith("{bcrypt}").doesNotContain("s3cret");
	}

	private void assertUnauthorized(MockHttpServletResponse response) throws Exception {
		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
		assertThat(response.getContentAsString()).isEqualTo(UNAUTHORIZED_BODY);
		assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
	}

}
