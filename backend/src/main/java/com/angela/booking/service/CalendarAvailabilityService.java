package com.angela.booking.service;

import com.angela.booking.client.GoogleCalendarClient;
import com.angela.booking.client.GoogleCalendarClient.BusyPeriod;
import com.angela.booking.entity.Slot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Finds slots that clash with events in the host's Google Calendar. Fails open: if the calendar is not
 * configured or Google can't be reached, no slot is treated as clashing (a warning is logged).
 */
@Service
public class CalendarAvailabilityService {

	private static final Logger log = LoggerFactory.getLogger(CalendarAvailabilityService.class);

	private final GoogleCalendarClient calendarClient;

	public CalendarAvailabilityService(GoogleCalendarClient calendarClient) {
		this.calendarClient = calendarClient;
	}

	/** IDs of the given slots that overlap a busy period in the calendar (one FreeBusy call for all of them). */
	public Set<Long> conflictingSlotIds(List<Slot> slots) {
		if (slots.isEmpty() || !calendarClient.isConfigured()) {
			return Set.of();
		}
		Instant from = slots.stream().map(Slot::getStartAt).min(Comparator.naturalOrder()).orElseThrow();
		Instant to = slots.stream().map(Slot::getEndAt).max(Comparator.naturalOrder()).orElseThrow();
		try {
			List<BusyPeriod> busy = calendarClient.busyPeriods(from, to);
			return slots.stream()
				.filter(slot -> busy.stream().anyMatch(period -> overlaps(slot, period)))
				.map(Slot::getId)
				.collect(Collectors.toSet());
		}
		catch (RuntimeException ex) {
			log.warn("Google Calendar check failed, treating slots as free: {}",
					NestedExceptionUtils.getMostSpecificCause(ex).toString());
			return Set.of();
		}
	}

	public boolean conflicts(Slot slot) {
		return conflictingSlotIds(List.of(slot)).contains(slot.getId());
	}

	/** Half-open ranges, like the slot_no_overlap constraint: an event ending at 10:00 doesn't block a 10:00 slot. */
	private static boolean overlaps(Slot slot, BusyPeriod period) {
		return period.start().isBefore(slot.getEndAt()) && period.end().isAfter(slot.getStartAt());
	}

}
