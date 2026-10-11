package com.rahul.booking_api.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.springframework.http.HttpStatus;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(int status, String error, String message, List<FieldError> fieldErrors) {

	public record FieldError(String field, String message) {
	}

	public static ApiError of(HttpStatus status, String message) {
		return new ApiError(status.value(), status.getReasonPhrase(), message, null);
	}

	public static ApiError of(HttpStatus status, String message, List<FieldError> fieldErrors) {
		return new ApiError(status.value(), status.getReasonPhrase(), message, fieldErrors);
	}

}
