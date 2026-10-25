package com.angela.booking.dto;

import com.angela.booking.entity.Booking;

import java.time.Instant;

/** Confirmation returned to the visitor who just booked. */
public record BookingResponse(Long id, Long slotId, Instant startAt, Instant endAt, int durationMinutes, String name,
		String email, Instant createdAt, String confirmationMessage) {

	public static BookingResponse from(Booking booking) {
		return new BookingResponse(booking.getId(), booking.getSlot().getId(), booking.getSlot().getStartAt(),
				booking.getSlot().getEndAt(), booking.getSlot().getDurationMinutes(), booking.getName(),
				booking.getEmail(), booking.getCreatedAt(), null);
	}

	public BookingResponse withConfirmationMessage(String message) {
		return new BookingResponse(id, slotId, startAt, endAt, durationMinutes, name, email, createdAt, message);
	}

}
