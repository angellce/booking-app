package com.angela.booking.service;

import com.angela.booking.dto.BookingRequest;
import com.angela.booking.dto.BookingResponse;
import com.angela.booking.entity.Booking;
import com.angela.booking.entity.Slot;
import com.angela.booking.exception.ConflictException;
import com.angela.booking.exception.NotFoundException;
import com.angela.booking.repository.BookingRepository;
import com.angela.booking.repository.SlotRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class BookingService {

	private static final String ALREADY_BOOKED_MESSAGE = "Sorry, this slot has already been booked.";

	private final BookingRepository bookingRepository;

	private final SlotRepository slotRepository;

	private final CalendarAvailabilityService calendarAvailabilityService;

	public BookingService(BookingRepository bookingRepository, SlotRepository slotRepository,
			CalendarAvailabilityService calendarAvailabilityService) {
		this.bookingRepository = bookingRepository;
		this.slotRepository = slotRepository;
		this.calendarAvailabilityService = calendarAvailabilityService;
	}

	/**
	 * Books a slot. Double bookings are prevented by the UNIQUE(slot_id) constraint on the booking table:
	 * if two requests race past the existsBySlotId check, PostgreSQL lets only one INSERT commit and the
	 * other fails with a unique violation, which is reported as 409 Conflict.
	 * <p>
	 * Deliberately not one big transaction: each repository call runs in its own, so no DB connection is held
	 * while waiting for Google Calendar. Correctness doesn't depend on it, because the constraint decides.
	 */
	public BookingResponse book(Long slotId, BookingRequest request) {
		Slot slot = slotRepository.findById(slotId)
			.orElseThrow(() -> new NotFoundException("Slot " + slotId + " was not found."));
		if (!slot.getStartAt().isAfter(Instant.now())) {
			throw new ConflictException("This slot has already started and can no longer be booked.");
		}
		if (bookingRepository.existsBySlotId(slotId)) {
			throw new ConflictException(ALREADY_BOOKED_MESSAGE);
		}
		// Application-level check against an external calendar (best effort, fails open)
		if (calendarAvailabilityService.conflicts(slot)) {
			throw new ConflictException("Sorry, this time is no longer available.");
		}
		try {
			Booking booking = bookingRepository
				.saveAndFlush(new Booking(slot, request.name().trim(), request.email().trim()));
			return BookingResponse.from(booking);
		}
		catch (DataIntegrityViolationException ex) {
			throw new ConflictException(ALREADY_BOOKED_MESSAGE);
		}
	}

}
