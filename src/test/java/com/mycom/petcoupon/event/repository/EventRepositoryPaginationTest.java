package com.mycom.petcoupon.event.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.entity.enums.EventStatus;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.entity.enums.UserRole;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EventRepositoryPaginationTest {

	@Autowired
	private EventRepository eventRepository;

	@PersistenceContext
	private EntityManager entityManager;

	@Test
	void openEventPageFiltersStatusAndUsesEventIdAsTieBreaker() {
		long existingOpenEvents = eventRepository
				.findAllByStatusOrderByCreatedAtDescEventIdDesc(EventStatus.OPEN, PageRequest.of(0, 1))
				.getTotalElements();
		AppUser admin = persistAdmin();
		Event firstOpen = persistEvent(admin, "첫 번째 공개 이벤트", EventStatus.OPEN);
		persistEvent(admin, "종료 이벤트", EventStatus.CLOSED);
		Event secondOpen = persistEvent(admin, "두 번째 공개 이벤트", EventStatus.OPEN);
		Event thirdOpen = persistEvent(admin, "세 번째 공개 이벤트", EventStatus.OPEN);
		alignCreatedAt(admin.getUserId());

		Page<Event> firstPage = eventRepository.findAllByStatusOrderByCreatedAtDescEventIdDesc(
				EventStatus.OPEN,
				PageRequest.of(0, 2)
		);
		Page<Event> secondPage = eventRepository.findAllByStatusOrderByCreatedAtDescEventIdDesc(
				EventStatus.OPEN,
				PageRequest.of(1, 2)
		);

		assertThat(firstPage.getTotalElements()).isEqualTo(existingOpenEvents + 3);
		assertThat(firstPage.getContent())
				.extracting(Event::getEventId)
				.containsExactly(thirdOpen.getEventId(), secondOpen.getEventId());
		assertThat(secondPage.getContent().getFirst().getEventId()).isEqualTo(firstOpen.getEventId());
	}

	@Test
	void adminEventPageIncludesEveryStatusAndUsesLatestOrder() {
		long existingEvents = eventRepository.count();
		AppUser admin = persistAdmin();
		persistEvent(admin, "예정 이벤트", EventStatus.SCHEDULED);
		Event open = persistEvent(admin, "진행 이벤트", EventStatus.OPEN);
		Event closed = persistEvent(admin, "종료 이벤트", EventStatus.CLOSED);
		alignCreatedAt(admin.getUserId());

		Page<Event> page = eventRepository.findAllByOrderByCreatedAtDescEventIdDesc(PageRequest.of(0, 2));

		assertThat(page.getTotalElements()).isEqualTo(existingEvents + 3);
		assertThat(page.getContent())
				.extracting(Event::getEventId)
				.containsExactly(closed.getEventId(), open.getEventId());
	}

	private AppUser persistAdmin() {
		AppUser admin = AppUser.builder()
				.name("페이지 테스트 관리자")
				.email("event-page-admin@test.com")
				.phone("010-0000-0000")
				.role(UserRole.ROLE_ADMIN)
				.build();
		entityManager.persist(admin);

		return admin;
	}

	private Event persistEvent(AppUser admin, String name, EventStatus status) {
		Event event = Event.builder()
				.createdBy(admin)
				.name(name)
				.description("페이지네이션 테스트")
				.openAt(LocalDateTime.of(2026, 8, 1, 0, 0))
				.closeAt(LocalDateTime.of(2026, 8, 31, 23, 59))
				.build();
		event.updateStatus(status);
		entityManager.persist(event);

		return event;
	}

	private void alignCreatedAt(Long adminId) {
		entityManager.flush();
		entityManager.createNativeQuery("UPDATE event SET created_at = :createdAt WHERE created_by = :adminId")
				.setParameter("createdAt", LocalDateTime.of(2100, 1, 1, 0, 0))
				.setParameter("adminId", adminId)
				.executeUpdate();
		entityManager.clear();
	}
}
