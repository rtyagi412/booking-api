package com.rahul.booking_api.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import com.rahul.booking_api.TestcontainersConfiguration;
import com.rahul.booking_api.customer.dto.CustomerResponse;
import com.rahul.booking_api.customer.dto.RegisterCustomerRequest;
import com.rahul.booking_api.customer.entity.Account;
import com.rahul.booking_api.customer.entity.Role;
import com.rahul.booking_api.customer.repository.AccountRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Deliberately not {@code @Transactional}: each service call runs in its own real transaction,
 * so failure paths exercise the service's rollback, and assertions afterwards use fresh transactions.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CustomerServiceTests {

	private static final String HASH = "{bcrypt}$2a$10$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz01234";

	@Autowired
	private CustomerService customerService;

	@MockitoSpyBean
	private AccountRepository accountRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@BeforeEach
	void cleanTable() {
		accountRepository.deleteAll();
	}

	@Test
	void registersCustomerWithNormalisedEmailAndCustomerRole() {
		CustomerResponse response = customerService.register(
				new RegisterCustomerRequest("  Asha Rao ", "  Asha@Example.COM ", "s3cret"));

		assertThat(response.id()).isNotNull();
		assertThat(response.name()).isEqualTo("Asha Rao");
		assertThat(response.email()).isEqualTo("asha@example.com");
		assertThat(response.role()).isEqualTo(Role.CUSTOMER);
		Account stored = onlyAccount();
		assertThat(stored.getId()).isEqualTo(response.id());
		assertThat(stored.getName()).isEqualTo("Asha Rao");
		assertThat(stored.getEmail()).isEqualTo("asha@example.com");
		assertThat(stored.getRole()).isEqualTo(Role.CUSTOMER);
	}

	@Test
	void serviceNormalisesEvenWhenRequestIsNotPreTrimmedByTheDto() {
		RegisterCustomerRequest request = new RegisterCustomerRequest("Asha", "ASHA@example.com", "x");

		assertThat(customerService.register(request).email()).isEqualTo("asha@example.com");
	}

	@Test
	void storesHashThatMatchesSuppliedPasswordIncludingSpaces() {
		customerService.register(new RegisterCustomerRequest("Asha", "asha@example.com", " abc "));

		String hash = onlyAccount().getPasswordHash();
		assertThat(hash).isNotBlank().isNotEqualTo(" abc ").startsWith("{bcrypt}");
		assertThat(passwordEncoder.matches(" abc ", hash)).isTrue();
		assertThat(passwordEncoder.matches("abc", hash)).isFalse();
	}

	@Test
	void responseDoesNotExposeThePasswordOrHash() {
		CustomerResponse response = customerService.register(
				new RegisterCustomerRequest("Asha", "asha@example.com", "TopSecret-123"));

		assertThat(response.toString()).doesNotContain("TopSecret-123").doesNotContain("{bcrypt}");
	}

	@ParameterizedTest
	@ValueSource(strings = {"asha@example.com", "ASHA@Example.COM", "  asha@example.com  ", " Asha@EXAMPLE.com\t"})
	void duplicateEmailVariantsAreRejectedAndLeaveOneRow(String variant) {
		customerService.register(new RegisterCustomerRequest("Asha", "asha@example.com", "x"));

		assertThatThrownBy(() -> customerService.register(new RegisterCustomerRequest("Other", variant, "y")))
				.isInstanceOf(EmailAlreadyRegisteredException.class)
				.hasMessage("email is already registered");
		assertThat(accountRepository.count()).isEqualTo(1);
	}

	@ParameterizedTest
	@EnumSource(value = Role.class, names = {"ADMIN", "MANAGER"})
	void emailOfSeededAdminOrManagerIsRejected(Role role) {
		accountRepository.saveAndFlush(account("Seeded", "staff@example.com", role));

		assertThatThrownBy(() -> customerService.register(
				new RegisterCustomerRequest("Asha", "  STAFF@example.com ", "x")))
				.isInstanceOf(EmailAlreadyRegisteredException.class);
		assertThat(accountRepository.count()).isEqualTo(1);
		assertThat(onlyAccount().getRole()).isEqualTo(role);
	}

	@Test
	void duplicateIsStillRejectedWhenPreliminaryLookupSaysEmailIsFree() {
		customerService.register(new RegisterCustomerRequest("Asha", "asha@example.com", "x"));
		doReturn(false).when(accountRepository).existsByEmail(anyString());
		clearInvocations(accountRepository);

		assertThatThrownBy(() -> customerService.register(
				new RegisterCustomerRequest("Other", "ASHA@example.com", "y")))
				.isInstanceOf(EmailAlreadyRegisteredException.class);

		verify(accountRepository).existsByEmail("asha@example.com");
		assertThat(accountRepository.count()).isEqualTo(1);
	}

	@Test
	void hibernateExposesTheUniqueConstraintNameOnTheCauseChain() {
		accountRepository.saveAndFlush(account("Asha", "asha@example.com", Role.CUSTOMER));

		DataIntegrityViolationException thrown = assertThrows(
				DataIntegrityViolationException.class,
				() -> accountRepository.saveAndFlush(account("Other", "asha@example.com", Role.CUSTOMER)));

		ConstraintViolationException violation = findConstraintViolation(thrown);
		assertThat(violation).as("Hibernate ConstraintViolationException in cause chain").isNotNull();
		assertThat(violation.getConstraintName()).isEqualTo("uk_accounts_email");
		assertThat(violation.getSQLState()).isEqualTo("23505");
		assertThat(violation.getKind()).isEqualTo(ConstraintViolationException.ConstraintKind.UNIQUE);
	}

	@Test
	void tooLongNameIsNotReportedAsDuplicateEmailAndLeavesNoRow() {
		RegisterCustomerRequest request = new RegisterCustomerRequest("n".repeat(33), "asha@example.com", "x");

		assertThatThrownBy(() -> customerService.register(request))
				.isInstanceOf(DataIntegrityViolationException.class)
				.isNotInstanceOf(EmailAlreadyRegisteredException.class);
		assertThat(accountRepository.count()).isZero();
	}

	@Test
	void tooLongEmailIsNotReportedAsDuplicateEmailAndLeavesNoRow() {
		RegisterCustomerRequest request = new RegisterCustomerRequest("Asha", "e".repeat(33), "x");

		assertThatThrownBy(() -> customerService.register(request))
				.isInstanceOf(DataIntegrityViolationException.class)
				.isNotInstanceOf(EmailAlreadyRegisteredException.class);
		assertThat(accountRepository.count()).isZero();
	}

	@Test
	void tooLongNameWithAnExistingEmailIsNotLabelledDuplicateWhenLookupIsBypassed() {
		customerService.register(new RegisterCustomerRequest("Asha", "asha@example.com", "x"));
		doReturn(false).when(accountRepository).existsByEmail(anyString());

		assertThatThrownBy(() -> customerService.register(
				new RegisterCustomerRequest("n".repeat(33), "asha@example.com", "y")))
				.isInstanceOf(DataIntegrityViolationException.class)
				.isNotInstanceOf(EmailAlreadyRegisteredException.class);
		assertThat(accountRepository.count()).isEqualTo(1);
	}

	@Test
	void sameNameWithDifferentEmailsIsAccepted() {
		customerService.register(new RegisterCustomerRequest("Asha", "one@example.com", "x"));
		customerService.register(new RegisterCustomerRequest("Asha", "two@example.com", "y"));

		assertThat(accountRepository.findAll()).extracting(Account::getName).containsExactly("Asha", "Asha");
		assertThat(accountRepository.count()).isEqualTo(2);
	}

	@Test
	void roleIsNeverTakenFromTheRequest() {
		// The request type has no role component; the service always assigns CUSTOMER.
		assertThat(customerService.register(new RegisterCustomerRequest("Asha", "asha@example.com", "x")).role())
				.isEqualTo(Role.CUSTOMER);
	}

	private Account onlyAccount() {
		assertThat(accountRepository.findAll()).hasSize(1);
		return accountRepository.findAll().get(0);
	}

	private Account account(String name, String email, Role role) {
		return Account.builder().name(name).email(email).passwordHash(HASH).role(role).build();
	}

	private ConstraintViolationException findConstraintViolation(Throwable exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation) {
				return violation;
			}
		}
		return null;
	}

}
