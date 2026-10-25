package com.angela.booking;

import com.angela.booking.client.GoogleCalendarClient.BusyPeriod;
import com.angela.booking.entity.Booking;
import com.angela.booking.entity.Slot;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BookingApiTests extends IntegrationTest {

	private static final int CONCURRENT_REQUESTS = 10;

	private ResultActions book(long slotId, String name, String email) throws Exception {
		return mockMvc.perform(post("/api/slots/{id}/booking", slotId).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"name": "%s", "email": "%s"}""".formatted(name, email)));
	}

	@Test
	void booksSlotAndRemovesItFromAvailableSlots() throws Exception {
		Slot slot = saveSlot(hoursFromNow(24), 30);

		book(slot.getId(), "Ann Smith", "ann@example.com").andExpect(status().isCreated())
			.andExpect(jsonPath("$.slotId").value(slot.getId()))
			.andExpect(jsonPath("$.name").value("Ann Smith"));

		mockMvc.perform(get("/api/slots")).andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void returnsLlmConfirmationMessageWithoutSendingTheEmail() throws Exception {
		Slot slot = saveSlot(Instant.parse("2099-06-01T08:00:00Z"), 30);
		doReturn(true).when(geminiClient).isConfigured();
		doReturn("See you soon, Ann!").when(geminiClient).generate(anyString(), anyString());

		mockMvc.perform(post("/api/slots/{id}/booking", slot.getId()).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"name": "Ann Smith", "email": "ann@example.com", "timeZone": "Europe/Skopje"}"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.confirmationMessage").value("See you soon, Ann!"));

		// Prompt has the first name and local time (UTC+2 in June), but no surname or email
		verify(geminiClient).generate(anyString(), argThat(prompt -> prompt.contains("Ann")
				&& prompt.contains("June 2099 at 10:00") && !prompt.contains("Smith") && !prompt.contains("@")));
	}

	@Test
	void bookingSucceedsWithFallbackMessageWhenLlmFails() throws Exception {
		Slot slot = saveSlot(Instant.parse("2099-06-01T08:00:00Z"), 30);
		doReturn(true).when(geminiClient).isConfigured();
		doThrow(new IllegalStateException("timeout")).when(geminiClient).generate(anyString(), anyString());

		book(slot.getId(), "Ann Smith", "ann@example.com").andExpect(status().isCreated())
			.andExpect(jsonPath("$.confirmationMessage")
				.value("Thanks, Ann! Your appointment on Monday 1 June 2099 at 08:00 (UTC) is confirmed. See you then!"));
		assertThat(bookingRepository.count()).isEqualTo(1);
	}

	@Test
	void rejectsBookingThatClashesWithHostCalendar() throws Exception {
		Slot slot = saveSlot(hoursFromNow(24), 30);
		doReturn(true).when(calendarClient).isConfigured();
		doReturn(List.of(new BusyPeriod(slot.getStartAt(), slot.getEndAt()))).when(calendarClient)
			.busyPeriods(any(), any());

		book(slot.getId(), "Ann", "ann@example.com").andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("Sorry, this time is no longer available."));
		assertThat(bookingRepository.count()).isZero();
	}

	@Test
	void rejectsSecondBookingOfSameSlot() throws Exception {
		Slot slot = saveSlot(hoursFromNow(24), 30);
		book(slot.getId(), "Ann", "ann@example.com").andExpect(status().isCreated());

		book(slot.getId(), "Bob", "bob@example.com").andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("Sorry, this slot has already been booked."));
	}

	@Test
	void returnsNotFoundForUnknownSlot() throws Exception {
		book(999_999, "Ann", "ann@example.com").andExpect(status().isNotFound());
	}

	@Test
	void rejectsInvalidNameAndEmail() throws Exception {
		Slot slot = saveSlot(hoursFromNow(24), 30);

		book(slot.getId(), " ", "not-an-email").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("name", "email")));
		assertThat(bookingRepository.count()).isZero();
	}

	@Test
	void exactlyOneOfManyConcurrentBookingsSucceeds() throws Exception {
		Slot slot = saveSlot(hoursFromNow(24), 30);
		HttpClient client = HttpClient.newHttpClient();
		CountDownLatch startGate = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();

		// Real HTTP requests on separate threads, released at the same moment
		try (ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS)) {
			for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
				HttpRequest request = HttpRequest
					.newBuilder(URI.create("http://localhost:" + port + "/api/slots/" + slot.getId() + "/booking"))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString("""
							{"name": "Visitor %d", "email": "visitor%d@example.com"}""".formatted(i, i)))
					.build();
				results.add(executor.submit(() -> {
					startGate.await();
					return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
				}));
			}
			startGate.countDown();
		}

		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> result : results) {
			statuses.add(result.get());
		}
		assertThat(statuses).containsOnly(201, 409);
		assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
		assertThat(bookingRepository.count()).isEqualTo(1);
	}

	@Test
	void databaseRejectsSecondBookingEvenWithoutApplicationCheck() {
		Slot slot = saveSlot(hoursFromNow(24), 30);
		bookingRepository.saveAndFlush(new Booking(slot, "Ann", "ann@example.com"));

		// Bypasses BookingService entirely: the UNIQUE(slot_id) constraint alone must stop it
		assertThatThrownBy(() -> bookingRepository.saveAndFlush(new Booking(slot, "Bob", "bob@example.com")))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

}
