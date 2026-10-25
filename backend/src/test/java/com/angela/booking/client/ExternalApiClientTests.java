package com.angela.booking.client;

import com.angela.booking.IntegrationTest;
import com.angela.booking.client.GoogleCalendarClient.BusyPeriod;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Checks the real request/response JSON mapping of the Google clients against realistic responses, using the
 * application's auto-configured RestClient (same Jackson setup as production). No network calls.
 */
class ExternalApiClientTests extends IntegrationTest {

	@Autowired
	private RestClient.Builder restClientBuilder;

	@Test
	void geminiSendsPromptAndReadsText() {
		MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
		GeminiClient client = new GeminiClient(restClientBuilder, "test-key", "gemini-3.5-flash-lite");

		server.expect(requestTo(
				"https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("x-goog-api-key", "test-key"))
			.andExpect(jsonPath("$.systemInstruction.parts[0].text").value("Be brief."))
			.andExpect(jsonPath("$.contents[0].parts[0].text").value("Hello"))
			.andRespond(withSuccess("""
					{
					  "candidates": [{
					    "content": {
					      "parts": [
					        {"text": "Hi Ann, ", "thoughtSignature": "abc123"},
					        {"text": "see you on Wednesday!"}
					      ],
					      "role": "model"
					    },
					    "finishReason": "STOP",
					    "index": 0
					  }],
					  "usageMetadata": {"promptTokenCount": 50, "candidatesTokenCount": 12, "totalTokenCount": 62},
					  "modelVersion": "gemini-3.5-flash-lite",
					  "responseId": "resp-1"
					}""", MediaType.APPLICATION_JSON));

		assertThat(client.generate("Be brief.", "Hello")).isEqualTo("Hi Ann, see you on Wednesday!");
		server.verify();
	}

	@Test
	void calendarSendsFreeBusyQueryAndReadsBusyPeriods() {
		MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
		GoogleCalendarClient client = new GoogleCalendarClient(restClientBuilder, "host@example.com",
				GoogleCredentials.create(new AccessToken("test-token", Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))));

		server.expect(requestTo("https://www.googleapis.com/calendar/v3/freeBusy"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", "Bearer test-token"))
			.andExpect(jsonPath("$.timeMin").value("2099-06-01T08:00:00Z"))
			.andExpect(jsonPath("$.timeMax").value("2099-06-01T12:00:00Z"))
			.andExpect(jsonPath("$.items[0].id").value("host@example.com"))
			.andRespond(withSuccess("""
					{
					  "kind": "calendar#freeBusy",
					  "timeMin": "2099-06-01T08:00:00.000Z",
					  "timeMax": "2099-06-01T12:00:00.000Z",
					  "calendars": {
					    "host@example.com": {
					      "busy": [{"start": "2099-06-01T09:00:00Z", "end": "2099-06-01T10:00:00Z"}]
					    }
					  }
					}""", MediaType.APPLICATION_JSON));
		server.expect(requestTo("https://www.googleapis.com/calendar/v3/freeBusy"))
			.andRespond(withSuccess("""
					{"calendars": {"host@example.com": {"errors": [{"domain": "global", "reason": "notFound"}], "busy": []}}}""",
					MediaType.APPLICATION_JSON));

		assertThat(client.busyPeriods(Instant.parse("2099-06-01T08:00:00Z"), Instant.parse("2099-06-01T12:00:00Z")))
			.containsExactly(new BusyPeriod(Instant.parse("2099-06-01T09:00:00Z"), Instant.parse("2099-06-01T10:00:00Z")));
		// Calendar not shared with the service account: reported as an error (the service then fails open)
		assertThatThrownBy(() -> client.busyPeriods(Instant.parse("2099-06-01T08:00:00Z"),
				Instant.parse("2099-06-01T12:00:00Z")))
			.hasMessageContaining("notFound");
		server.verify();
	}

}
