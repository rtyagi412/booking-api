package com.rahul.booking_api.customer.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import com.rahul.booking_api.TestcontainersConfiguration;
import com.rahul.booking_api.customer.dto.CustomerResponse;
import com.rahul.booking_api.customer.entity.Role;
import com.rahul.booking_api.customer.service.CustomerService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * T22: runs against a real servlet container so that ERROR dispatch, the security chain's
 * ERROR permit and Boot's default error rendering are exercised together (MockMvc does not do this).
 * The forced failure lives here, isolated from the normal controller tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class CustomerRegistrationServerErrorTests {

	private static final String MARKER = "SENSITIVE-MARKER jdbc:postgresql://host/db select * from accounts";
	private static final String VALID_BODY = "{\"name\":\"Asha\",\"email\":\"asha@example.com\",\"password\":\"s3cret\"}";
	private static final List<String> FORBIDDEN_FRAGMENTS = List.of("SENSITIVE-MARKER", "jdbc", "select", "Exception",
			"java.", "com.rahul", "org.springframework", "trace", "s3cret", "asha@example.com");

	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	@LocalServerPort
	private int port;

	@MockitoBean
	private CustomerService customerService;

	@BeforeEach
	void failRegistration() {
		doThrow(new RuntimeException(MARKER)).when(customerService).register(any());
	}

	@Test
	void unexpectedFailureReturns500WithoutSensitiveDetailsAndServerStaysHealthy() throws Exception {
		HttpResponse<String> response = send(jsonPost(VALID_BODY));

		assertThat(response.statusCode()).isEqualTo(500);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				type -> assertThat(type).startsWith("application/json"));
		assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
		assertNoSensitiveContent(response.body());
		assertThat(response.body()).doesNotContain("<html").doesNotContain("\"exception\"").doesNotContain("\"trace\"");

		doReturn(new CustomerResponse(7L, "Asha", "asha@example.com", Role.CUSTOMER))
				.when(customerService).register(any());

		HttpResponse<String> healthy = send(jsonPost(VALID_BODY));
		assertThat(healthy.statusCode()).isEqualTo(201);
		assertThat(healthy.body()).isEqualTo("{\"id\":7,\"name\":\"Asha\",\"email\":\"asha@example.com\",\"role\":\"CUSTOMER\"}");
		assertThat(healthy.headers().allValues("Set-Cookie")).isEmpty();
	}

	@Test
	void unsupportedContentTypeReturns415ThroughErrorDispatchNotUnauthorized() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(uri("/api/customers"))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "text/plain")
				.POST(HttpRequest.BodyPublishers.ofString("hello"))
				.build();

		HttpResponse<String> response = send(request);

		assertThat(response.statusCode()).isEqualTo(415);
		assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
		assertNoSensitiveContent(response.body());
	}

	@Test
	void anonymousProtectedRequestStillReturnsTheSpecified401OverARealSocket() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(uri("/api/customers"))
				.timeout(Duration.ofSeconds(10)).GET().build();

		HttpResponse<String> response = send(request);

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.body())
				.isEqualTo("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"authentication required\"}");
		assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
	}

	private void assertNoSensitiveContent(String body) {
		for (String fragment : FORBIDDEN_FRAGMENTS) {
			assertThat(body).as("response body must not contain '%s'", fragment).doesNotContain(fragment);
		}
	}

	private HttpRequest jsonPost(String body) {
		return HttpRequest.newBuilder(uri("/api/customers"))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
		return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
	}

}
