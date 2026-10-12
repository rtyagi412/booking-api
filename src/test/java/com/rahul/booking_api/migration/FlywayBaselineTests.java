package com.rahul.booking_api.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves the one-time baseline of a database created by Hibernate before Flyway: V1 is skipped, existing
 * rows are untouched, and the resulting schema equals a fresh V1 database. Uses its own throwaway containers.
 */
@Testcontainers
class FlywayBaselineTests {

	private static final String ROWS = "select string_agg(t::text, '|' order by id) from accounts t";

	private static final String SCHEMA_SIGNATURE = """
			select string_agg(s, ';' order by s) from (
			  select 'col ' || column_name || ':' || data_type || ':' || coalesce(character_maximum_length::text, '-')
			         || ':' || is_nullable || ':' || is_identity || ':' || coalesce(identity_generation, '-') as s
			  from information_schema.columns where table_schema = 'public' and table_name = 'accounts'
			  union all
			  select 'con ' || conname || ':' || contype::text || ':' || pg_get_constraintdef(oid)
			  from pg_constraint where conrelid = 'public.accounts'::regclass
			  union all
			  select 'idx ' || indexdef from pg_indexes where schemaname = 'public' and tablename = 'accounts'
			) t
			""";

	@Container
	private static final PostgreSQLContainer LEGACY = new PostgreSQLContainer(DockerImageName.parse("postgres:17"));

	@Container
	private static final PostgreSQLContainer FRESH = new PostgreSQLContainer(DockerImageName.parse("postgres:17"));

	@Test
	void baselineSkipsV1PreservesDataAndYieldsTheSameSchemaAsAFreshMigration() throws Exception {
		JdbcTemplate legacy = jdbc(LEGACY);
		String legacyDdl = new ClassPathResource("legacy-hibernate-schema.sql")
				.getContentAsString(StandardCharsets.UTF_8);
		for (String statement : legacyDdl.split(";")) {
			if (!statement.replaceAll("(?m)^--.*$", "").isBlank()) {
				legacy.execute(statement);
			}
		}
		legacy.update("insert into accounts (name, email, password_hash, role) "
				+ "values ('Asha', 'asha@example.com', 'hash-1', 'CUSTOMER')");
		String rowsBefore = legacy.queryForObject(ROWS, String.class);

		flyway(LEGACY, true).migrate();

		List<Map<String, Object>> history = legacy.queryForList(
				"select version, type, success from flyway_schema_history order by installed_rank");
		assertThat(history).hasSize(1);
		assertThat(history.get(0)).containsEntry("version", "1").containsEntry("type", "BASELINE")
				.containsEntry("success", true);
		assertThat(legacy.queryForObject(ROWS, String.class)).isEqualTo(rowsBefore);
		assertThat(flyway(LEGACY, false).info().pending()).isEmpty();

		flyway(FRESH, false).migrate();
		assertThat(legacy.queryForObject(SCHEMA_SIGNATURE, String.class))
				.isEqualTo(jdbc(FRESH).queryForObject(SCHEMA_SIGNATURE, String.class));
	}

	@Test
	void populatedDatabaseWithoutHistoryFailsUnlessBaselineIsExplicitlyEnabled() {
		try (PostgreSQLContainer other = new PostgreSQLContainer(DockerImageName.parse("postgres:17"))) {
			other.start();
			jdbc(other).execute("create table accounts (id bigint primary key)");

			assertThatThrownBy(() -> flyway(other, false).migrate()).isInstanceOf(FlywayException.class)
					.hasMessageContaining("non-empty schema");
		}
	}

	private static Flyway flyway(PostgreSQLContainer container, boolean baseline) {
		return Flyway.configure()
				.dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
				.locations("classpath:db/migration")
				.baselineOnMigrate(baseline)
				.baselineVersion("1")
				.load();
	}

	private static JdbcTemplate jdbc(PostgreSQLContainer container) {
		return new JdbcTemplate(new DriverManagerDataSource(container.getJdbcUrl(), container.getUsername(),
				container.getPassword()));
	}

}
