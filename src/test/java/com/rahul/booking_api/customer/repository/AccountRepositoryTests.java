package com.rahul.booking_api.customer.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rahul.booking_api.TestcontainersConfiguration;
import com.rahul.booking_api.customer.entity.Account;
import com.rahul.booking_api.customer.entity.Role;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AccountRepositoryTests {

	private static final String HASH = "{bcrypt}$2a$10$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz01234";

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private EntityManager entityManager;

	@BeforeEach
	void cleanTable() {
		accountRepository.deleteAll();
	}

	@Test
	void containerRunsPostgres17() {
		Object version = entityManager.createNativeQuery("select version()").getSingleResult();

		assertThat(version.toString()).startsWith("PostgreSQL 17");
	}

	@Test
	void savesAccountAndGeneratesId() {
		Account saved = accountRepository.saveAndFlush(account("Asha", "asha@example.com", Role.CUSTOMER));

		assertThat(saved.getId()).isNotNull();
		assertThat(accountRepository.findById(saved.getId())).get()
				.satisfies(found -> {
					assertThat(found.getName()).isEqualTo("Asha");
					assertThat(found.getEmail()).isEqualTo("asha@example.com");
					assertThat(found.getPasswordHash()).isEqualTo(HASH);
					assertThat(found.getRole()).isEqualTo(Role.CUSTOMER);
				});
	}

	@Test
	void rejectsDuplicateEmailViaNamedUniqueConstraint() {
		accountRepository.saveAndFlush(account("Asha", "asha@example.com", Role.CUSTOMER));

		assertThatThrownBy(() -> accountRepository.saveAndFlush(account("Other", "asha@example.com", Role.CUSTOMER)))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uk_accounts_email");
		assertThat(accountRepository.count()).isEqualTo(1);
	}

	@Test
	void rejectsNullName() {
		assertViolation(account(null, "a@example.com", Role.CUSTOMER));
	}

	@Test
	void rejectsNullEmail() {
		assertViolation(account("Asha", null, Role.CUSTOMER));
	}

	@Test
	void rejectsNullPasswordHash() {
		assertViolation(Account.builder().name("Asha").email("a@example.com").role(Role.CUSTOMER).build());
	}

	@Test
	void rejectsNullRole() {
		assertViolation(account("Asha", "a@example.com", null));
	}

	@Test
	void rejectsNameLongerThan32Characters() {
		assertViolation(account("n".repeat(33), "a@example.com", Role.CUSTOMER));
	}

	@Test
	void rejectsEmailLongerThan32Characters() {
		assertViolation(account("Asha", "e".repeat(33), Role.CUSTOMER));
	}

	@Test
	void acceptsNameAndEmailOfExactly32Characters() {
		accountRepository.saveAndFlush(account("n".repeat(32), "e".repeat(32), Role.CUSTOMER));

		assertThat(accountRepository.count()).isEqualTo(1);
	}

	@Test
	void persistsManagerAndAdminRoles() {
		accountRepository.saveAndFlush(account("Mia", "manager@example.com", Role.MANAGER));
		accountRepository.saveAndFlush(account("Ada", "admin@example.com", Role.ADMIN));

		assertThat(accountRepository.findAll()).extracting(Account::getRole)
				.containsExactlyInAnyOrder(Role.MANAGER, Role.ADMIN);
	}

	@Test
	void allowsSameNameWithDifferentEmails() {
		accountRepository.saveAndFlush(account("Asha", "one@example.com", Role.CUSTOMER));
		accountRepository.saveAndFlush(account("Asha", "two@example.com", Role.CUSTOMER));

		assertThat(accountRepository.count()).isEqualTo(2);
	}

	@Test
	void existsByEmailReportsPresenceAndAbsence() {
		accountRepository.saveAndFlush(account("Asha", "asha@example.com", Role.CUSTOMER));

		assertThat(accountRepository.existsByEmail("asha@example.com")).isTrue();
		assertThat(accountRepository.existsByEmail("nobody@example.com")).isFalse();
	}

	private void assertViolation(Account account) {
		assertThatThrownBy(() -> accountRepository.saveAndFlush(account))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(accountRepository.count()).isZero();
	}

	private Account account(String name, String email, Role role) {
		return Account.builder().name(name).email(email).passwordHash(HASH).role(role).build();
	}

}
