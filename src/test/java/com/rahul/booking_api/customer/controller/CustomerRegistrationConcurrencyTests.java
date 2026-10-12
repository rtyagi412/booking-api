package com.rahul.booking_api.customer.controller;

import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.rahul.booking_api.TestcontainersConfiguration;
import com.rahul.booking_api.customer.repository.AccountRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T15. All waits are bounded; the executor is always shut down and must terminate.
 * 8 workers stay below the default Hikari pool size of 10.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CustomerRegistrationConcurrencyTests {

	private static final int THREADS = 8;
	private static final long WAIT_SECONDS = 10;
	private static final long RESULT_SECONDS = 20;
	private static final String DUPLICATE = "email is already registered";
	private static final String[] EMAIL_VARIANTS = {
			"asha@example.com", "  ASHA@example.com", "Asha@Example.COM  ", " asha@EXAMPLE.com "};

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AccountRepository accountRepository;

	@BeforeEach
	void cleanTable() {
		accountRepository.deleteAll();
	}

	@RepeatedTest(3)
	@Timeout(30)
	void simultaneousRegistrationsOfTheSameEmailCreateExactlyOneAccount() throws Exception {
		List<String> bodies = IntStream.range(0, THREADS)
				.mapToObj(i -> body("Asha " + i, EMAIL_VARIANTS[i % EMAIL_VARIANTS.length]))
				.toList();

		List<MockHttpServletResponse> responses = runConcurrently(bodies);

		assertThat(responses).extracting(MockHttpServletResponse::getStatus)
				.doesNotContain(500)
				.containsOnly(201, 409);
		assertThat(responses).filteredOn(r -> r.getStatus() == 201).hasSize(1);
		for (MockHttpServletResponse conflict : responses.stream().filter(r -> r.getStatus() == 409).toList()) {
			assertThat((String) JsonPath.read(conflict.getContentAsString(), "$.message")).isEqualTo(DUPLICATE);
		}
		assertThat(accountRepository.count()).isEqualTo(1);
		assertThat(accountRepository.existsByEmail("asha@example.com")).isTrue();
	}

	@Test
	@Timeout(30)
	void simultaneousRegistrationsOfDifferentEmailsAllSucceed() throws Exception {
		List<String> bodies = IntStream.range(0, THREADS)
				.mapToObj(i -> body("Asha", "user" + i + "@example.com"))
				.toList();

		List<MockHttpServletResponse> responses = runConcurrently(bodies);

		assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsOnly(201).hasSize(THREADS);
		assertThat(accountRepository.count()).isEqualTo(THREADS);
	}

	private List<MockHttpServletResponse> runConcurrently(List<String> bodies) throws Exception {
		int workers = bodies.size();
		ExecutorService executor = Executors.newFixedThreadPool(workers);
		try {
			CountDownLatch ready = new CountDownLatch(workers);
			CountDownLatch start = new CountDownLatch(1);
			List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
			for (String body : bodies) {
				futures.add(executor.submit(worker(body, ready, start)));
			}
			if (!ready.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
				fail("workers were not ready within " + WAIT_SECONDS + "s");
			}
			start.countDown();
			List<MockHttpServletResponse> responses = new ArrayList<>();
			for (Future<MockHttpServletResponse> future : futures) {
				responses.add(future.get(RESULT_SECONDS, TimeUnit.SECONDS));
			}
			return responses;
		}
		finally {
			executor.shutdownNow();
			if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
				fail("worker threads did not terminate");
			}
		}
	}

	private Callable<MockHttpServletResponse> worker(String body, CountDownLatch ready, CountDownLatch start) {
		return () -> {
			ready.countDown();
			if (!start.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
				throw new IllegalStateException("start signal not received within " + WAIT_SECONDS + "s");
			}
			return mockMvc.perform(post("/api/customers")
							.contentType(MediaType.APPLICATION_JSON)
							.content(body.getBytes(StandardCharsets.UTF_8)))
					.andReturn().getResponse();
		};
	}

	private static String body(String name, String email) {
		return "{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"password\":\"s3cret\"}";
	}

}
