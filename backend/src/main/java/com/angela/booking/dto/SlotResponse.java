package com.angela.booking.dto;

import com.angela.booking.entity.Slot;

import java.time.Instant;

/**
 * Public view of a slot. Booker details are deliberately not exposed, since the API has no authentication.
 * {@code calendarConflict} is true when the slot clashes with an event in the host's Google Calendar.
 */
public record SlotResponse(Long id, Instant startAt, Instant endAt, int durationMinutes, boolean booked,
		boolean calendarConflict) {

	public static SlotResponse from(Slot slot, boolean booked, boolean calendarConflict) {
		return new SlotResponse(slot.getId(), slot.getStartAt(), slot.getEndAt(), slot.getDurationMinutes(), booked,
				calendarConflict);
	}

}
