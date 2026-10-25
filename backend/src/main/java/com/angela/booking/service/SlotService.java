package com.angela.booking.service;

import com.angela.booking.dto.CreateSlotRequest;
import com.angela.booking.dto.SlotResponse;
import com.angela.booking.entity.Slot;
import com.angela.booking.exception.ConflictException;
import com.angela.booking.repository.BookingRepository;
import com.angela.booking.repository.SlotRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
public class SlotService {

	private static final String OVERLAP_MESSAGE = "This slot overlaps an existing slot.";

	private final SlotRepository slotRepository;

	private final BookingRepository bookingRepository;

	private final CalendarAvailabilityService calendarAvailabilityService;

	public SlotService(SlotRepository slotRepository, BookingRepository bookingRepository,
			CalendarAvailabilityService calendarAvailabilityService) {
		this.slotRepository = slotRepository;
		this.bookingRepository = bookingRepository;
		this.calendarAvailabilityService = calendarAvailabilityService;
	}

	@Transactional
	public SlotResponse create(CreateSlotRequest request) {
		Instant startAt = request.startAt();
		Instant endAt = startAt.plus(Duration.ofMinutes(request.durationMinutes()));
		// Friendly early check; the slot_no_overlap exclusion constraint is the real guarantee under concurrency
		if (slotRepository.existsOverlapping(startAt, endAt)) {
			throw new ConflictException(OVERLAP_MESSAGE);
		}
		try {
			Slot slot = slotRepository.saveAndFlush(new Slot(startAt, request.durationMinutes()));
			return SlotResponse.from(slot, false, false);
		}
		catch (DataIntegrityViolationException ex) {
			throw new ConflictException(OVERLAP_MESSAGE);
		}
	}

	/**
	 * Upcoming slots. Visitors get only bookable ones (not booked, no Google Calendar clash); the host gets all of
	 * them with booked/calendarConflict flags. Not transactional, so no DB connection is held during the
	 * Google Calendar call.
	 */
	public List<SlotResponse> listUpcoming(boolean includeBooked) {
		Instant now = Instant.now();
		if (!includeBooked) {
			List<Slot> available = slotRepository.findAvailableAfter(now);
			Set<Long> clashing = calendarAvailabilityService.conflictingSlotIds(available);
			return available.stream()
				.filter(slot -> !clashing.contains(slot.getId()))
				.map(slot -> SlotResponse.from(slot, false, false))
				.toList();
		}
		List<Slot> slots = slotRepository.findByStartAtAfterOrderByStartAt(now);
		Set<Long> bookedIds = bookingRepository.findBookedSlotIds(slots.stream().map(Slot::getId).toList());
		Set<Long> clashing = calendarAvailabilityService.conflictingSlotIds(slots);
		return slots.stream()
			.map(slot -> SlotResponse.from(slot, bookedIds.contains(slot.getId()), clashing.contains(slot.getId())))
			.toList();
	}

}
