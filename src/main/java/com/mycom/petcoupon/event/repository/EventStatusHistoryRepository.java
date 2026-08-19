package com.mycom.petcoupon.event.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mycom.petcoupon.event.entity.EventStatusHistory;

public interface EventStatusHistoryRepository extends JpaRepository<EventStatusHistory, Long> {

	@Modifying(flushAutomatically = true)
	@Query(
			value = """
					INSERT INTO event_status_history (
						event_id,
						from_status,
						to_status,
						actor_type,
						actor_id,
						reason,
						created_at
					) VALUES (
						:eventId,
						'NONE',
						'SCHEDULED',
						'ADMIN',
						:actorId,
						:reason,
						CURRENT_TIMESTAMP(6)
					)
					""",
			nativeQuery = true
	)
	void insertInitialHistory(
			@Param("eventId") Long eventId,
			@Param("actorId") Long actorId,
			@Param("reason") String reason
	);
}
