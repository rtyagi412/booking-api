package com.rahul.booking_api.error;

import com.rahul.booking_api.customer.service.EmailAlreadyRegisteredException;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Comparator<ApiError.FieldError> BY_FIELD_THEN_MESSAGE =
			Comparator.comparing(ApiError.FieldError::field).thenComparing(ApiError.FieldError::message);

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
		List<ApiError.FieldError> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
				.map(error -> new ApiError.FieldError(error.getField(), error.getDefaultMessage()))
				.sorted(BY_FIELD_THEN_MESSAGE)
				.toList();
		return respond(ApiError.of(HttpStatus.BAD_REQUEST, "invalid request", fieldErrors));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ApiError> handleUnreadableBody() {
		return respond(ApiError.of(HttpStatus.BAD_REQUEST, "malformed request body"));
	}

	@ExceptionHandler(EmailAlreadyRegisteredException.class)
	ResponseEntity<ApiError> handleDuplicateEmail(EmailAlreadyRegisteredException exception) {
		return respond(ApiError.of(HttpStatus.CONFLICT, exception.getMessage()));
	}

	private ResponseEntity<ApiError> respond(ApiError body) {
		return ResponseEntity.status(body.status()).body(body);
	}

}
