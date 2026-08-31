package com.mycom.petcoupon.event.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.entity.enums.EventStatus;

public interface EventRepository extends JpaRepository<Event, Long> {
	Page<Event> findAllByStatusOrderByCreatedAtDescEventIdDesc(EventStatus status, Pageable pageable);

	Page<Event> findAllByOrderByCreatedAtDescEventIdDesc(Pageable pageable);

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

	// 오픈 시각(openAt)이 지났는데도 여전히 SCHEDULED인 이벤트 (스케줄러가 OPEN으로 전환할 대상)
	List<Event> findByStatusAndOpenAtLessThanEqual(EventStatus status, LocalDateTime now);

	// 종료 시각(closeAt)이 지났는데도 여전히 OPEN인 이벤트 (스케줄러가 CLOSED로 전환할 대상)
	List<Event> findByStatusAndCloseAtLessThanEqual(EventStatus status, LocalDateTime now);

	// 대시보드 요약 집계(#172)용 — 전체 이벤트 수는 JpaRepository.count()로 충분해서 따로
	// 안 만들고, 진행 중인(OPEN) 이벤트 수만 별도로 센다.
	long countByStatus(EventStatus status);
}
