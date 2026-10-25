package com.angela.booking;

import com.angela.booking.client.GoogleCalendarClient.BusyPeriod;
import com.angela.booking.entity.Booking;
import com.angela.booking.entity.Slot;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SlotApiTests extends IntegrationTest {

	@Test
	void hidesCalendarClashesFromVisitorsAndMarksThemForHost() throws Exception {
		Instant ten = hoursFromNow(24);
		Slot clashing = saveSlot(ten, 60);
		Slot backToBack = saveSlot(ten.plusSeconds(3600), 30);
		// Calendar event 10:30–11:00 clashes with the 10:00–11:00 slot but not with the 11:00 slot (half-open)
		doReturn(true).when(calendarClient).isConfigured();
		doReturn(List.of(new BusyPeriod(ten.plusSeconds(1800), ten.plusSeconds(3600)))).when(calendarClient)
			.busyPeriods(any(), any());

		mockMvc.perform(get("/api/slots"))
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].id").value(backToBack.getId()));
		mockMvc.perform(get("/api/slots").param("includeBooked", "true"))
			.andExpect(jsonPath("$", hasSize(2)))
			.andExpect(jsonPath("$[0].id").value(clashing.getId()))
			.andExpect(jsonPath("$[0].calendarConflict").value(true))
			.andExpect(jsonPath("$[1].calendarConflict").value(false));
	}

	@Test
	void showsSlotsWhenCalendarIsUnreachable() throws Exception {
		saveSlot(hoursFromNow(24), 60);
		doReturn(true).when(calendarClient).isConfigured();
		doThrow(new IllegalStateException("Google down")).when(calendarClient).busyPeriods(any(), any());

		mockMvc.perform(get("/api/slots")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
	}

	private ResultActions createSlot(Instant startAt, int durationMinutes) throws Exception {
		return mockMvc.perform(post("/api/slots").contentType(MediaType.APPLICATION_JSON).content("""
				{"startAt": "%s", "durationMinutes": %d}""".formatted(startAt, durationMinutes)));
	}

	@Test
	void createsSlot() throws Exception {
		Instant start = hoursFromNow(24);

		createSlot(start, 30).andExpect(status().isCreated())
			.andExpect(jsonPath("$.startAt").value(start.toString()))
			.andExpect(jsonPath("$.endAt").value(start.plusSeconds(30 * 60).toString()))
			.andExpect(jsonPath("$.durationMinutes").value(30))
			.andExpect(jsonPath("$.booked").value(false));
	}

	@Test
	void rejectsPastStartAndUnreasonableDuration() throws Exception {
		createSlot(hoursFromNow(-1), 4).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.title").value("Validation failed"))
			.andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("startAt", "durationMinutes")));
		createSlot(hoursFromNow(24), 481).andExpect(status().isBadRequest());
	}

	@Test
	void rejectsOverlapButAllowsBackToBackSlots() throws Exception {
		Instant start = hoursFromNow(24);
		createSlot(start, 60).andExpect(status().isCreated());

		createSlot(start.plusSeconds(30 * 60), 60).andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("This slot overlaps an existing slot."));
		createSlot(start.plusSeconds(60 * 60), 30).andExpect(status().isCreated());
	}

	@Test
	void listsUpcomingAvailableSlotsAndHostViewIncludesBooked() throws Exception {
		Slot later = saveSlot(hoursFromNow(48), 30);
		Slot sooner = saveSlot(hoursFromNow(24), 30);
		Slot booked = saveSlot(hoursFromNow(30), 30);
		saveSlot(hoursFromNow(-2), 30);
		bookingRepository.save(new Booking(booked, "Ann", "ann@example.com"));

		mockMvc.perform(get("/api/slots"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(2)))
			.andExpect(jsonPath("$[0].id").value(sooner.getId()))
			.andExpect(jsonPath("$[1].id").value(later.getId()));

		mockMvc.perform(get("/api/slots").param("includeBooked", "true"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(3)))
			.andExpect(jsonPath("$[1].id").value(booked.getId()))
			.andExpect(jsonPath("$[1].booked").value(true));
	}

}
