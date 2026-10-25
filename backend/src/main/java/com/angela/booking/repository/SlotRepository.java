package com.angela.booking.repository;

import com.angela.booking.entity.Slot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface SlotRepository extends JpaRepository<Slot, Long> {

	/** Upcoming slots that have no booking yet. */
	@Query("""
			select s from Slot s
			where s.startAt > :now
			  and not exists (select b.id from Booking b where b.slot = s)
			order by s.startAt""")
	List<Slot> findAvailableAfter(Instant now);

	List<Slot> findByStartAtAfterOrderByStartAt(Instant now);

	/** Mirrors the slot_no_overlap constraint: ranges are half-open, so touching slots do not overlap. */
	@Query("select count(s) > 0 from Slot s where s.startAt < :endAt and s.endAt > :startAt")
	boolean existsOverlapping(Instant startAt, Instant endAt);

}
