package com.angela.booking;

import com.angela.booking.client.GeminiClient;
import com.angela.booking.client.GoogleCalendarClient;
import com.angela.booking.entity.Slot;
import com.angela.booking.repository.BookingRepository;
import com.angela.booking.repository.SlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Base class for tests against a real PostgreSQL started by Testcontainers (requires Docker).
 * Flyway migrations and DB constraints run exactly as in production. All subclasses share one
 * Spring context and one container; tables are emptied before each test.
 */
// External services are forced off so tests never call Google, even if backend/.env configures them
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = { "app.gemini.api-key=",
		"app.google-calendar.calendar-id=", "app.google-calendar.service-account-file=" })
@AutoConfigureMockMvc
@Import(IntegrationTest.PostgresConfig.class)
public abstract class IntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class PostgresConfig {

		@Bean
		@ServiceConnection
		PostgreSQLContainer postgresContainer() {
			return new PostgreSQLContainer("postgres:17-alpine");
		}

	}

	// Spies on the real beans (so their wiring is tested); unconfigured by default: template message, no calendar
	@MockitoSpyBean
	protected GeminiClient geminiClient;

	@MockitoSpyBean
	protected GoogleCalendarClient calendarClient;

	@Autowired
	protected MockMvc mockMvc;

	@Autowired
	protected SlotRepository slotRepository;

	@Autowired
	protected BookingRepository bookingRepository;

	@LocalServerPort
	protected int port;

	@BeforeEach
	void cleanDatabase() {
		bookingRepository.deleteAllInBatch();
		slotRepository.deleteAllInBatch();
	}

	/** A whole-minute instant {@code hours} from now (negative for the past). */
	protected static Instant hoursFromNow(long hours) {
		return Instant.now().truncatedTo(ChronoUnit.MINUTES).plus(hours, ChronoUnit.HOURS);
	}

	protected Slot saveSlot(Instant startAt, int durationMinutes) {
		return slotRepository.save(new Slot(startAt, durationMinutes));
	}

}
