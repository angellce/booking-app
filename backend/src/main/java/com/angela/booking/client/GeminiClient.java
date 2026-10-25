package com.angela.booking.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Minimal client for the Gemini API (Google AI Studio) generateContent endpoint.
 * The API key comes from GEMINI_API_KEY; without it the client reports itself as not configured.
 * Timeouts are set globally with spring.http.clients.* in application.properties.
 */
@Component
public class GeminiClient {

	private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta";

	private final RestClient restClient;

	private final String apiKey;

	private final String model;

	public GeminiClient(RestClient.Builder builder, @Value("${app.gemini.api-key}") String apiKey,
			@Value("${app.gemini.model}") String model) {
		this.restClient = builder.baseUrl(BASE_URL).build();
		this.apiKey = apiKey;
		this.model = model;
	}

	public boolean isConfigured() {
		return !apiKey.isBlank();
	}

	/** Returns the generated text; throws on HTTP errors, timeouts or an empty answer. */
	public String generate(String systemInstruction, String prompt) {
		GenerateRequest request = new GenerateRequest(new Content(List.of(new Part(systemInstruction))),
				List.of(new Content(List.of(new Part(prompt)))));
		GenerateResponse response = restClient.post()
			.uri("/models/{model}:generateContent", model)
			.header("x-goog-api-key", apiKey)
			.body(request)
			.retrieve()
			.body(GenerateResponse.class);
		String text = response == null || response.candidates() == null ? ""
				: response.candidates()
					.stream()
					.findFirst()
					.map(candidate -> candidate.content() == null || candidate.content().parts() == null ? ""
							: candidate.content()
								.parts()
								.stream()
								.filter(part -> !Boolean.TRUE.equals(part.thought()))
								.map(ResponsePart::text)
								.filter(Objects::nonNull)
								.collect(Collectors.joining()))
					.orElse("");
		if (text.isBlank()) {
			throw new IllegalStateException("Gemini returned no text");
		}
		return text.strip();
	}

	record GenerateRequest(Content systemInstruction, List<Content> contents) {
	}

	record Content(List<Part> parts) {
	}

	record Part(String text) {
	}

	record GenerateResponse(List<Candidate> candidates) {
	}

	record Candidate(ResponseContent content) {
	}

	record ResponseContent(List<ResponsePart> parts) {
	}

	// Boxed: "thought" is usually absent, and Jackson 3 rejects a missing value for a primitive boolean
	record ResponsePart(String text, Boolean thought) {
	}

}
