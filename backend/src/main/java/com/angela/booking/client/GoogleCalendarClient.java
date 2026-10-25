package com.angela.booking.client;

import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Reads busy times of the host's Google Calendar with the FreeBusy API, authenticated as a service account
 * that the calendar is shared with ("See only free/busy"). Only busy ranges are returned, never event details.
 * Disabled unless both GOOGLE_CALENDAR_ID and GOOGLE_SERVICE_ACCOUNT_FILE are set.
 */
@Component
public class GoogleCalendarClient {

	private static final String SCOPE = "https://www.googleapis.com/auth/calendar.freebusy";

	public record BusyPeriod(Instant start, Instant end) {
	}

	private final RestClient restClient;

	private final String calendarId;

	private final GoogleCredentials credentials;

	@Autowired
	public GoogleCalendarClient(RestClient.Builder builder, @Value("${app.google-calendar.calendar-id}") String calendarId,
			@Value("${app.google-calendar.service-account-file}") String serviceAccountFile) {
		this(builder, calendarId, loadCredentials(calendarId, serviceAccountFile));
	}

	GoogleCalendarClient(RestClient.Builder builder, String calendarId, GoogleCredentials credentials) {
		this.restClient = builder.baseUrl("https://www.googleapis.com/calendar/v3").build();
		this.calendarId = calendarId;
		this.credentials = credentials;
	}

	/** Fails fast at startup if the calendar is configured but the key file can't be read. */
	private static GoogleCredentials loadCredentials(String calendarId, String serviceAccountFile) {
		if (calendarId.isBlank() || serviceAccountFile.isBlank()) {
			return null;
		}
		try (InputStream in = new FileInputStream(serviceAccountFile)) {
			return GoogleCredentials.fromStream(in).createScoped(SCOPE);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot read Google service account file " + serviceAccountFile, ex);
		}
	}

	public boolean isConfigured() {
		return credentials != null;
	}

	/** Busy periods in [from, to); throws if Google can't be reached or reports an error for the calendar. */
	public List<BusyPeriod> busyPeriods(Instant from, Instant to) {
		FreeBusyResponse response = restClient.post()
			.uri("/freeBusy")
			.header("Authorization", "Bearer " + accessToken())
			.body(new FreeBusyRequest(from, to, "UTC", List.of(new CalendarItem(calendarId))))
			.retrieve()
			.body(FreeBusyResponse.class);
		CalendarBusy calendar = response == null || response.calendars() == null ? null
				: response.calendars().get(calendarId);
		if (calendar == null) {
			throw new IllegalStateException("No free/busy data returned for the calendar");
		}
		if (calendar.errors() != null && !calendar.errors().isEmpty()) {
			// e.g. "notFound" when the calendar isn't shared with the service account
			throw new IllegalStateException("Google Calendar error: " + calendar.errors().getFirst().reason());
		}
		return calendar.busy() == null ? List.of() : calendar.busy();
	}

	private String accessToken() {
		try {
			credentials.refreshIfExpired();
			return credentials.getAccessToken().getTokenValue();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot get Google access token", ex);
		}
	}

	record FreeBusyRequest(Instant timeMin, Instant timeMax, String timeZone, List<CalendarItem> items) {
	}

	record CalendarItem(String id) {
	}

	record FreeBusyResponse(Map<String, CalendarBusy> calendars) {
	}

	record CalendarBusy(List<BusyPeriod> busy, List<CalendarError> errors) {
	}

	record CalendarError(String domain, String reason) {
	}

}
