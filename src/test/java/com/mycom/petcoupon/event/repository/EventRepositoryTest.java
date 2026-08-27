package com.mycom.petcoupon.event.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.entity.enums.EventStatus;
import com.mycom.petcoupon.user.entity.AppUser;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 대시보드 요약 집계(#172)용 countByStatus 검증 — derived query 자체는 단순하지만,
 * EventStatus enum이 실제로 올바르게 매핑되는지는 목으로는 확인 못 한다.
 *
 * 실행 전 MySQL이 떠 있어야 한다: docker compose up -d mysql
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EventRepositoryTest {

	@PersistenceContext
	private EntityManager entityManager;

	@Autowired
	private EventRepository eventRepository;

	private AppUser user;

	@BeforeEach
	void setUp() {
		user = AppUser.builder()
				.name("EventRepository 테스트 사용자")
				.email("event-repo-test@test.com")
				.phone("010-1234-5678")
				.build();
		entityManager.persist(user);
	}

	// countByStatus()는 다른 테스트/실행이 남긴 이벤트가 섞여 있을 수 있는 전체 집계라,
	// 절대값이 아니라 "이 테스트가 새로 만든 만큼 늘었는지"(delta)로 검증한다
	// (IssueMessageRepositoryTest의 countGroupedByStatus 검증과 같은 이유).
	@Test
	void countByStatus는_해당_상태의_이벤트_수만_정확히_센다() {
		long openBefore = eventRepository.countByStatus(EventStatus.OPEN);

		persistEvent("countByStatus-scheduled");
		Event open = persistEvent("countByStatus-open");
		open.updateStatus(EventStatus.OPEN);

		entityManager.flush();
		entityManager.clear();

		long openAfter = eventRepository.countByStatus(EventStatus.OPEN);

		// SCHEDULED 상태로 남긴 하나는 안 잡히고, OPEN으로 바꾼 하나만 델타에 잡혀야 한다.
		assertThat(openAfter - openBefore).isEqualTo(1L);
	}

	private Event persistEvent(String name) {
		LocalDateTime now = LocalDateTime.now();
		Event event = Event.builder()
				.createdBy(user)
				.name(name)
				.description("설명")
				.openAt(now.minusHours(1))
				.closeAt(now.plusDays(1))
				.build();
		entityManager.persist(event);
		return event;
	}
}
