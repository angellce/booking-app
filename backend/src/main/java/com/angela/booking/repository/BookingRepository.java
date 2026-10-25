package com.angela.booking.repository;

import com.angela.booking.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.Set;

public interface BookingRepository extends JpaRepository<Booking, Long> {

	boolean existsBySlotId(Long slotId);

	@Query("select b.slot.id from Booking b where b.slot.id in :slotIds")
	Set<Long> findBookedSlotIds(Collection<Long> slotIds);

}
