package com.angela.booking.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param timeZone optional IANA time zone of the visitor (e.g. {@code Europe/Skopje}), used only to word the
 * confirmation message; unknown values fall back to UTC
 */
public record BookingRequest(
		@NotBlank(message = "Name is required")
		@Size(max = 100, message = "Name must be at most 100 characters")
		String name,

		@NotBlank(message = "Email is required")
		@Email(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "Email must be a valid email address")
		@Size(max = 254, message = "Email must be at most 254 characters")
		String email,

		@Size(max = 64, message = "Time zone must be at most 64 characters")
		String timeZone) {
}
