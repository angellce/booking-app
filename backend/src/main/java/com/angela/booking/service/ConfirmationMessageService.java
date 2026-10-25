package com.angela.booking.service;

import com.angela.booking.client.GeminiClient;
import com.angela.booking.dto.BookingResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Writes a short, friendly booking confirmation with Gemini. Booking never depends on it: if Gemini is not
 * configured, slow or failing, a fixed template message is returned instead.
 */
@Service
public class ConfirmationMessageService {

	private static final Logger log = LoggerFactory.getLogger(ConfirmationMessageService.class);

	private static final int MAX_LENGTH = 500;

	private static final String SYSTEM_INSTRUCTION = """
			You write booking confirmations for a small appointment scheduling app.
			Write a short, warm confirmation of 1 to 2 sentences in plain text (no markdown, no emoji).
			Address the visitor by first name and mention the date and time exactly as given.
			Do not invent any other details such as a location, a host name or contact information.""";

	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy 'at' HH:mm",
			Locale.ENGLISH);

	private final GeminiClient geminiClient;

	public ConfirmationMessageService(GeminiClient geminiClient) {
		this.geminiClient = geminiClient;
	}

	public String confirmationMessage(BookingResponse booking, String timeZone) {
		// Only the first name and the slot time are sent to the LLM, never the email address
		String firstName = booking.name().strip().split("\\s+")[0];
		ZoneId zone = parseZone(timeZone);
		String when = DATE_TIME.format(booking.startAt().atZone(zone))
				+ (zone.equals(ZoneOffset.UTC) ? " (UTC)" : "");
		String fallback = "Thanks, %s! Your appointment on %s is confirmed. See you then!".formatted(firstName, when);

		if (!geminiClient.isConfigured()) {
			return fallback;
		}
		try {
			String message = geminiClient.generate(SYSTEM_INSTRUCTION,
					"Visitor first name: %s%nAppointment: %s, %d minutes".formatted(firstName, when,
							booking.durationMinutes()));
			return message.length() > MAX_LENGTH ? fallback : message;
		}
		catch (RuntimeException ex) {
			log.warn("Gemini confirmation failed, using fallback message: {}",
					NestedExceptionUtils.getMostSpecificCause(ex).toString());
			return fallback;
		}
	}

	private static ZoneId parseZone(String timeZone) {
		if (timeZone == null || timeZone.isBlank()) {
			return ZoneOffset.UTC;
		}
		try {
			return ZoneId.of(timeZone);
		}
		catch (DateTimeException ex) {
			return ZoneOffset.UTC;
		}
	}

}
