package com.rahul.booking_api.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rahul.booking_api.TestcontainersConfiguration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FlywayMigrationTests {

	private static final String INSERT = "insert into accounts (name, email, password_hash, role) values (?, ?, 'h', ?)";

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void v1IsAppliedSuccessfullyOnAFreshDatabase() {
		List<Map<String, Object>> history = jdbcTemplate.queryForList(
				"select version, type, success from flyway_schema_history order by installed_rank");

		assertThat(history).hasSize(1);
		assertThat(history.get(0)).containsEntry("version", "1").containsEntry("type", "SQL")
				.containsEntry("success", true);
	}

	@Test
	void accountsColumnsMatchTheEntityMapping() {
		List<String> columns = jdbcTemplate.query("""
				select column_name || ':' || data_type || ':' || coalesce(character_maximum_length::text, '-')
				       || ':' || is_nullable || ':' || is_identity || ':' || coalesce(identity_generation, '-')
				from information_schema.columns where table_name = 'accounts' order by ordinal_position
				""", (rs, i) -> rs.getString(1));

		assertThat(columns).containsExactly(
				"id:bigint:-:NO:YES:BY DEFAULT",
				"email:character varying:32:NO:NO:-",
				"name:character varying:32:NO:NO:-",
				"password_hash:character varying:255:NO:NO:-",
				"role:character varying:16:NO:NO:-");
	}

	@Test
	void accountsConstraintsAreNamedAsExpected() {
		List<String> constraints = jdbcTemplate.query(
				"select conname || ':' || contype::text from pg_constraint where conrelid = 'accounts'::regclass order by 1",
				(rs, i) -> rs.getString(1));

		assertThat(constraints).containsExactly("accounts_pkey:p", "accounts_role_check:c", "uk_accounts_email:u");
	}

	@Test
	void roleCheckConstraintRejectsUnknownRole() {
		assertThatThrownBy(() -> jdbcTemplate.update(INSERT, "Bad", "bad-role@example.com", "HACKER"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("accounts_role_check");
	}

	@Test
	void uniqueEmailIsEnforcedByTheDatabase() {
		jdbcTemplate.update(INSERT, "A", "dup-flyway@example.com", "CUSTOMER");
		try {
			assertThatThrownBy(() -> jdbcTemplate.update(INSERT, "B", "dup-flyway@example.com", "CUSTOMER"))
					.isInstanceOf(DataIntegrityViolationException.class)
					.hasMessageContaining("uk_accounts_email");
		}
		finally {
			jdbcTemplate.update("delete from accounts where email = 'dup-flyway@example.com'");
		}
	}

}
