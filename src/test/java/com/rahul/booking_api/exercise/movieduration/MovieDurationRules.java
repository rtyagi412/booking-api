package com.rahul.booking_api.exercise.movieduration;

final class MovieDurationRules {

	private MovieDurationRules() {
	}

	static boolean isValidDuration(int minutes) {
		return minutes > 0;
	}

}
