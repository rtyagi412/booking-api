package com.rahul.booking_api.exercise.movieduration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MovieDurationRulesTests {

	@ParameterizedTest(name = "{0} minutes -> {1}")
	@CsvSource({
			"-1, false",
			"0, false",
			"1, true",
			"120, true"
	})
	void durationMustBeGreaterThanZero(int minutes, boolean expected) {
		assertThat(MovieDurationRules.isValidDuration(minutes)).isEqualTo(expected);
	}

}
