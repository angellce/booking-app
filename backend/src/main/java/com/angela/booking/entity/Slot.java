package com.angela.booking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;

@Entity
@Table(name = "slot")
public class Slot {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "start_at", nullable = false)
	private Instant startAt;

	@Column(name = "end_at", nullable = false)
	private Instant endAt;

	@Column(name = "duration_minutes", nullable = false)
	private int durationMinutes;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected Slot() {
		// for JPA
	}

	public Slot(Instant startAt, int durationMinutes) {
		this.startAt = startAt;
		this.durationMinutes = durationMinutes;
		this.endAt = startAt.plus(Duration.ofMinutes(durationMinutes));
		this.createdAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public Instant getStartAt() {
		return startAt;
	}

	public Instant getEndAt() {
		return endAt;
	}

	public int getDurationMinutes() {
		return durationMinutes;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
