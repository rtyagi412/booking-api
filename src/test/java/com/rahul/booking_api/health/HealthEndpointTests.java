package com.rahul.booking_api.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rahul.booking_api.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class HealthEndpointTests {

	private static final String UP_BODY = "{\"status\":\"UP\"}";
	private static final String UNAUTHORIZED_BODY =
			"{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"authentication required\"}";

	@Autowired
	private MockMvc mockMvc;

	@ParameterizedTest
	@ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness"})
	void anonymousProbeGetIsUpWithOnlyStatus(String path) throws Exception {
		mockMvc.perform(get(path))
				.andExpect(status().isOk())
				.andExpect(content().json(UP_BODY, true));
	}

	@Test
	void anonymousAggregateGetIsUpWithoutComponents() throws Exception {
		String body = mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(content().json(UP_BODY, false))
				.andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain("components", "details");
	}

	@ParameterizedTest
	@ValueSource(strings = {"/actuator", "/actuator/env", "/actuator/beans", "/actuator/info", "/actuator/metrics",
			"/actuator/health/db", "/actuator/health/liveness/extra"})
	void otherActuatorPathsRemainProtected(String path) throws Exception {
		MockHttpServletResponse response = mockMvc.perform(get(path)).andReturn().getResponse();

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentAsString()).isEqualTo(UNAUTHORIZED_BODY);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
	void postToHealthIsNotPermitted(String path) throws Exception {
		MockHttpServletResponse response = mockMvc.perform(post(path)).andReturn().getResponse();

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentAsString()).isEqualTo(UNAUTHORIZED_BODY);
	}

	@Test
	void healthResponsesDoNotLeakInternals() throws Exception {
		String body = mockMvc.perform(get("/actuator/health/readiness")).andReturn().getResponse()
				.getContentAsString();

		assertThat(body).doesNotContain("components", "details", "db", "postgres", "jdbc", "localhost");
	}

}
