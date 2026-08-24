package com.mycom.petcoupon.event.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.entity.enums.EventStatus;

public interface EventRepository extends JpaRepository<Event, Long> {

	@Query("select e.status from Event e where e.eventId = :eventId")
	Optional<EventStatus> findStatusByEventId(@Param("eventId") Long eventId);

	@Modifying(clearAutomatically = true)
	@Query("""
			UPDATE Event e
			   SET e.status = :toStatus
			 WHERE e.eventId = :eventId
			   AND e.status = :fromStatus
			""")
	int updateStatusIfMatches(
			@Param("eventId") Long eventId,
			@Param("fromStatus") EventStatus fromStatus,
			@Param("toStatus") EventStatus toStatus
	);
}
