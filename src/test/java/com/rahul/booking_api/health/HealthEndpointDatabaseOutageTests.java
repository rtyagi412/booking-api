package com.rahul.booking_api.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Database outage without touching any shared container: the datasource points at a local port that
 * was just released, so connections are refused immediately and deterministically. Hibernate's JDBC
 * metadata access is disabled so the context starts without a reachable database.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
		"spring.datasource.username=outage",
		"spring.datasource.password=outage",
		"spring.datasource.hikari.initialization-fail-timeout=-1",
		"spring.datasource.hikari.connection-timeout=250",
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
		"spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false"
})
@AutoConfigureMockMvc
class HealthEndpointDatabaseOutageTests {

	@Autowired
	private MockMvc mockMvc;

	@DynamicPropertySource
	static void unreachableDatabase(DynamicPropertyRegistry registry) throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}
		registry.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:" + closedPort + "/outage");
	}

	@Test
	void livenessStaysUpWhenDatabaseIsDown() throws Exception {
		mockMvc.perform(get("/actuator/health/liveness"))
				.andExpect(status().isOk())
				.andExpect(content().json("{\"status\":\"UP\"}", true));
	}

	@Test
	void readinessIsDownWithoutDetailsWhenDatabaseIsDown() throws Exception {
		String body = mockMvc.perform(get("/actuator/health/readiness"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(content().json("{\"status\":\"DOWN\"}", true))
				.andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain("outage", "127.0.0.1", "jdbc", "Connection", "refused");
	}

	@Test
	void aggregateHealthIsDownWhenDatabaseIsDown() throws Exception {
		String body = mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(content().json("{\"status\":\"DOWN\"}", false))
				.andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain("components", "details", "127.0.0.1");
	}

}
