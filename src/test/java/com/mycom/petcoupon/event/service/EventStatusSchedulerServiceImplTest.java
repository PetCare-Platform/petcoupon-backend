package com.mycom.petcoupon.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.entity.EventStatusHistory;
import com.mycom.petcoupon.event.entity.enums.ActorType;
import com.mycom.petcoupon.event.entity.enums.EventHistoryStatus;
import com.mycom.petcoupon.event.entity.enums.EventStatus;
import com.mycom.petcoupon.event.repository.EventRepository;
import com.mycom.petcoupon.event.repository.EventStatusHistoryRepository;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.entity.enums.UserRole;
import com.mycom.petcoupon.user.repository.AppUserRepository;

/**
 * cron을 1초 주기로 덮어써서 실제 스케줄러 실행이 이벤트 상태를 바꾸는지 확인한다.
 * 고정 Thread.sleep 대신 Awaitility로 폴링해서, 조건이 충족되는 즉시 끝나고
 * 실패 시에도 지정한 시간까지는 재시도하도록 한다 (CI 부하로 인한 flaky 완화).
 *
 * Redis Stream Consumer는 꺼둔다. 이벤트 스케줄러는 Redis와 무관한데, @SpringBootTest가 전체
 * 컨텍스트를 올리면서 couponIssueStreamContainer 빈까지 생성해 Redis 연결을 시도하기 때문이다.
 * 켜두면 Redis가 없을 때 이 테스트가 컨텍스트 로딩 단계에서 통째로 실패한다.
 *
 * 실행 전 MySQL이 떠 있어야 한다: docker compose up -d mysql
 */
@SpringBootTest(properties = {
		"event.status.scheduler.cron=*/1 * * * * *",
		"coupon.issue.stream.enabled=false"
})
class EventStatusSchedulerServiceImplTest {

	private static final Duration AT_MOST = Duration.ofSeconds(5);
	private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

	@Autowired
	private EventRepository eventRepository;

	@Autowired
	private EventStatusHistoryRepository eventStatusHistoryRepository;

	@Autowired
	private AppUserRepository appUserRepository;

	private final List<Event> createdEvents = new ArrayList<>();
	private final List<AppUser> createdUsers = new ArrayList<>();

	@AfterEach
	void tearDown() {
		for (Event event : createdEvents) {
			eventStatusHistoryRepository.deleteAll(eventStatusHistoryRepository.findByEvent_EventId(event.getEventId()));
		}
		eventRepository.deleteAll(createdEvents);
		appUserRepository.deleteAll(createdUsers);
		createdEvents.clear();
		createdUsers.clear();
	}

	@Test
	void 오픈_시각이_지난_SCHEDULED_이벤트는_스케줄러가_자동으로_OPEN으로_전환한다() {
		Event event = createEvent(LocalDateTime.now().minusSeconds(5), LocalDateTime.now().plusDays(1));

		await().atMost(AT_MOST).pollInterval(POLL_INTERVAL).untilAsserted(() ->
				assertThat(eventRepository.findById(event.getEventId()).orElseThrow().getStatus())
						.isEqualTo(EventStatus.OPEN)
		);

		EventStatusHistory history = onlyHistoryOf(event);
		assertThat(history.getFromStatus()).isEqualTo(EventHistoryStatus.SCHEDULED);
		assertThat(history.getToStatus()).isEqualTo(EventHistoryStatus.OPEN);
		assertThat(history.getActorType()).isEqualTo(ActorType.SCHEDULER);
		assertThat(history.getReason()).isEqualTo("오픈 시각 도달");
	}

	@Test
	void 종료_시각이_지난_OPEN_이벤트는_스케줄러가_자동으로_CLOSED로_전환한다() {
		Event event = createEvent(LocalDateTime.now().minusDays(1), LocalDateTime.now().minusSeconds(5));
		event.updateStatus(EventStatus.OPEN);
		eventRepository.save(event);

		await().atMost(AT_MOST).pollInterval(POLL_INTERVAL).untilAsserted(() ->
				assertThat(eventRepository.findById(event.getEventId()).orElseThrow().getStatus())
						.isEqualTo(EventStatus.CLOSED)
		);

		EventStatusHistory history = onlyHistoryOf(event);
		assertThat(history.getFromStatus()).isEqualTo(EventHistoryStatus.OPEN);
		assertThat(history.getToStatus()).isEqualTo(EventHistoryStatus.CLOSED);
		assertThat(history.getReason()).isEqualTo("종료 시각 도달");
	}

	@Test
	void 오픈_종료_시각이_모두_지난_SCHEDULED_이벤트는_CLOSED까지_전환된다() {
		Event event = createEvent(LocalDateTime.now().minusDays(2), LocalDateTime.now().minusSeconds(5));

		await().atMost(AT_MOST).pollInterval(POLL_INTERVAL).untilAsserted(() ->
				assertThat(eventRepository.findById(event.getEventId()).orElseThrow().getStatus())
						.isEqualTo(EventStatus.CLOSED)
		);

		List<EventStatusHistory> histories = eventStatusHistoryRepository.findByEvent_EventId(event.getEventId());
		assertThat(histories)
				.extracting(EventStatusHistory::getToStatus)
				.containsExactlyInAnyOrder(EventHistoryStatus.OPEN, EventHistoryStatus.CLOSED);
	}

	@Test
	void 오픈_시각이_아직_안된_이벤트는_전환되지_않는다() {
		Event event = createEvent(LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(2));

		// "전환되지 않는다"는 한 번 확인하고 끝낼 수 없으니, 일정 시간 동안 계속 SCHEDULED로 유지되는지 확인한다.
		await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(4)).untilAsserted(() ->
				assertThat(eventRepository.findById(event.getEventId()).orElseThrow().getStatus())
						.isEqualTo(EventStatus.SCHEDULED)
		);
		assertThat(eventStatusHistoryRepository.findByEvent_EventId(event.getEventId())).isEmpty();
	}

	private Event createEvent(LocalDateTime openAt, LocalDateTime closeAt) {
		AppUser user = appUserRepository.save(AppUser.builder()
				.name("스케줄러테스트회원")
				.email("scheduler-test-" + UUID.randomUUID() + "@test.com")
				.phone("010-1234-5678")
				.role(UserRole.ROLE_MEMBER)
				.build());
		createdUsers.add(user);

		Event event = eventRepository.save(Event.builder()
				.createdBy(user)
				.name("스케줄러 테스트 이벤트")
				.description("scheduler status test")
				.openAt(openAt)
				.closeAt(closeAt)
				.build());
		createdEvents.add(event);

		return event;
	}

	private EventStatusHistory onlyHistoryOf(Event event) {
		List<EventStatusHistory> histories = eventStatusHistoryRepository.findByEvent_EventId(event.getEventId());
		assertThat(histories).hasSize(1);
		return histories.get(0);
	}
}
