package com.angela.booking.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * @param startAt ISO-8601 instant with offset, e.g. {@code 2030-01-15T09:00:00+01:00}
 */
public record CreateSlotRequest(
		@NotNull(message = "Start time is required")
		@Future(message = "Start time must be in the future")
		Instant startAt,

		@NotNull(message = "Duration is required")
		@Min(value = 5, message = "Duration must be at least 5 minutes")
		@Max(value = 480, message = "Duration must be at most 480 minutes (8 hours)")
		Integer durationMinutes) {
}
