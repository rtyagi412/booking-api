package com.rahul.booking_api.customer.service;

import com.rahul.booking_api.customer.dto.CustomerResponse;
import com.rahul.booking_api.customer.dto.RegisterCustomerRequest;
import com.rahul.booking_api.customer.entity.Account;
import com.rahul.booking_api.customer.entity.Role;
import com.rahul.booking_api.customer.repository.AccountRepository;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomerService {

	private static final String EMAIL_UNIQUE_CONSTRAINT = "uk_accounts_email";

	private final AccountRepository accountRepository;
	private final PasswordEncoder passwordEncoder;

	@Transactional
	public CustomerResponse register(RegisterCustomerRequest request) {
		String email = request.email().trim().toLowerCase(Locale.ROOT);
		if (accountRepository.existsByEmail(email)) {
			throw new EmailAlreadyRegisteredException();
		}
		Account account = Account.builder()
				.name(request.name().trim())
				.email(email)
				.passwordHash(passwordEncoder.encode(request.password()))
				.role(Role.CUSTOMER)
				.build();
		return CustomerResponse.from(save(account));
	}

	private Account save(Account account) {
		try {
			return accountRepository.saveAndFlush(account);
		} catch (DataIntegrityViolationException exception) {
			if (violatesEmailUniqueness(exception)) {
				throw new EmailAlreadyRegisteredException();
			}
			throw exception;
		}
	}

	private boolean violatesEmailUniqueness(Throwable exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation
					&& EMAIL_UNIQUE_CONSTRAINT.equalsIgnoreCase(violation.getConstraintName())) {
				return true;
			}
		}
		return false;
	}

}
