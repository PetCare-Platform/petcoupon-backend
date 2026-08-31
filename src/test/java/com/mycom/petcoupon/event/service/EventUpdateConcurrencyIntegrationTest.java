package com.mycom.petcoupon.event.service;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mycom.petcoupon.event.dto.req.EventUpdateRequest;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.repository.EventRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.repository.AppUserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 이벤트 동시 수정 lost update 방어를 <b>서비스 경로로</b> 검증한다.
 *
 * <p>이전 버전(EventPessimisticLockIntegrationTest)은 테스트가 직접
 * {@code eventRepository.findByIdForUpdate()}를 부르고 엔티티를 고쳤다. 그러면 검증 대상이
 * "JPA 비관적 락이 동작한다"가 되어버려서, 정작 이 PR이 바꾼 한 줄
 * ({@code updateEvent}의 findById → findByIdForUpdate)을 되돌려도 그대로 통과한다.
 * 막으려는 회귀를 못 잡는 테스트였다. 그래서 전부 {@link EventService#updateEvent}를 거친다.
 *
 * <p>{@code @DataJpaTest}가 아니라 {@code @SpringBootTest}인 이유이기도 하다 — 서비스 빈이 필요하다.
 * 자동 롤백이 없으므로 tearDown에서 직접 지운다.
 *
 * <p>실행 전 MySQL과 Redis가 떠 있어야 한다: docker compose up -d
 */
@SpringBootTest(properties = {
		// 스케줄러가 돌면 event 행 상태를 바꿔서 검증과 무관한 경합이 생긴다.
		"event.status.scheduler.enabled=false",
		"coupon.status.enabled=false",
		"coupon.issue.stream.enabled=false",
		"coupon.issue.outbox.enabled=false",
		"spring.kafka.listener.auto-startup=false"
})
class EventUpdateConcurrencyIntegrationTest {

	private static final String ORIGINAL_NAME = "원래 이벤트";

	@PersistenceContext
	private EntityManager entityManager;

	@Autowired
	private EventService eventService;

	@Autowired
	private EventRepository eventRepository;

	@Autowired
	private AppUserRepository appUserRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private TransactionTemplate transactionTemplate;
	private Long eventId;
	private Long userId;

	@BeforeEach
	void setUp() {
		transactionTemplate = new TransactionTemplate(transactionManager);
		transactionTemplate.executeWithoutResult(status -> {
			AppUser user = appUserRepository.save(AppUser.builder()
					.name("동시 수정 테스트 사용자")
					.email("event-update-concurrency-" + UUID.randomUUID() + "@test.com")
					.phone("010-1234-5678")
					.build());

			// openAt을 미래로 둔다. 과거로 두면 SCHEDULED + openAt<=now라 상태 전이 대상이 되는데,
			// 이 클래스가 스케줄러를 껐어도 같은 MySQL을 쓰는 다른 테스트 컨텍스트의 스케줄러가
			// 이 행을 집어가 event_status_history를 남기고, 그러면 tearDown의 delete가 FK로 깨진다
			// (application.properties의 event.status.scheduler.enabled 주석이 경고하는 그 상황).
			LocalDateTime now = LocalDateTime.now();
			Event event = eventRepository.save(Event.builder()
					.createdBy(user)
					.name(ORIGINAL_NAME)
					.description("원래 설명")
					.openAt(now.plusHours(1))
					.closeAt(now.plusDays(1))
					.build());

			userId = user.getUserId();
			eventId = event.getEventId();
		});
	}

	@AfterEach
	void tearDown() {
		transactionTemplate.executeWithoutResult(status -> {
			if (eventId != null) {
				// 그래도 이력이 남았다면(다른 컨텍스트의 스케줄러 등) FK 때문에 event를 못 지운다.
				// 정리 실패가 이 테스트의 실패로 둔갑하지 않도록 자식 행을 먼저 지운다.
				entityManager.createNativeQuery(
								"DELETE FROM event_status_history WHERE event_id = :eventId")
						.setParameter("eventId", eventId)
						.executeUpdate();
				eventRepository.deleteById(eventId);
			}
			if (userId != null) {
				appUserRepository.deleteById(userId);
			}
		});
	}

	@Test
	@DisplayName("기간 일부만 수정할 때 동시에 바뀐 나머지 값을 낡은 채로 검증하지 않는다")
	void partialPeriodUpdateValidatesAgainstFreshCloseAt() throws Exception {
		/*
		 * 이 PR의 회귀 감지기다. findByIdForUpdate를 findById로 되돌리면 깨진다.
		 *
		 * 감지기를 두 번 갈아엎고 나서야 여기 도달했으니 이유를 남겨둔다.
		 *  - "락을 쥐면 대기하는가"로는 못 잡는다. 더티체킹 UPDATE도 커밋 시점에 같은 행 락이
		 *    필요해서, findById여도 읽기가 아니라 커밋에서 블록될 뿐 결국 대기한다(실측).
		 *  - "낡은 값을 되돌려 쓰는가"로도 못 잡는다. @DynamicUpdate가 있으면 낡은 closeAt은
		 *    스냅샷과 같은 값이라 dirty가 아니고, 그래서 UPDATE 문에서 아예 빠진다(실측).
		 *
		 * 락만이 막는 건 "검증이 낡은 값을 본다"는 쪽이다. updateEvent는 openAt만 온 요청의
		 * closeAt을 자기 스냅샷에서 채워 validatePeriod에 넘긴다. 그 스냅샷이 낡으면 이미
		 * 앞당겨진 closeAt을 못 보고 통과시켜, closeAt <= openAt인 이벤트가 DB에 남는다.
		 */
		// 동시에 closeAt을 "앞으로 당긴다" — 요청한 openAt보다 앞선 시각으로.
		LocalDateTime farCloseAt = LocalDateTime.now().plusHours(2).withNano(0);
		LocalDateTime newOpenAt = LocalDateTime.now().plusHours(5).withNano(0);

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch lockedAndChanged = new CountDownLatch(1);
		CountDownLatch releaseLock = new CountDownLatch(1);

		try {
			Future<?> lockHolder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
				entityManager.createNativeQuery(
								"SELECT event_id FROM event WHERE event_id = :eventId FOR UPDATE")
						.setParameter("eventId", eventId)
						.getSingleResult();
				entityManager.createNativeQuery(
								"UPDATE event SET close_at = :closeAt WHERE event_id = :eventId")
						.setParameter("closeAt", farCloseAt)
						.setParameter("eventId", eventId)
						.executeUpdate();

				lockedAndChanged.countDown();
				await(releaseLock);
			}));

			await(lockedAndChanged);

			Future<?> partialUpdate = executor.submit(() -> eventService.updateEvent(eventId,
					EventUpdateRequest.builder().openAt(newOpenAt).build()));

			// 락이 없는 구현이라면 이 사이에 낡은 close_at을 읽어버린다. 락이 있으면 아직 SELECT에서 대기 중이다.
			Thread.sleep(300);

			releaseLock.countDown();
			lockHolder.get(5, SECONDS);

			/*
			 * 락을 잡고 신선한 closeAt으로 검증하면 이 요청은 INVALID_EVENT_PERIOD로 거절된다.
			 * (락 대기 시간을 NOWAIT로 좁히면 PessimisticLockingFailureException으로도 끝날 수
			 *  있으므로 둘 다 허용한다.) 어느 쪽이든 아래 불변식이 깨지지 않는 것이 계약이다.
			 */
			try {
				partialUpdate.get(5, SECONDS);
			} catch (ExecutionException expectedWhenRejected) {
				assertThat(expectedWhenRejected.getCause())
						.isInstanceOfAny(GeneralException.class, PessimisticLockingFailureException.class);
			}
		} finally {
			releaseLock.countDown();
			executor.shutdownNow();
		}

		Event persisted = reload();
		// 불변식: 종료가 시작보다 뒤여야 한다. 락이 없으면 낡은 closeAt(now+1d)으로 검증을
		// 통과해버려서 openAt=now+5h / closeAt=now+2h인 이벤트가 그대로 남는다.
		assertThat(persisted.getCloseAt())
				.as("종료 시각은 항상 시작 시각보다 뒤여야 한다")
				.isAfter(persisted.getOpenAt());
	}

	@Test
	@DisplayName("같은 필드를 동시에 수정해도 값이 유실되지 않고 나중 커밋이 남는다")
	void concurrentSameFieldUpdatesKeepTheLastCommittedValue() throws Exception {
		/*
		 * PR 본문이 "같은 필드를 동시에 수정하면 락 획득 순서에 따라 나중 요청의 값이 최종
		 * 반영된다"고 주장하던 자리다. 기존 테스트는 이름 vs 기간(서로 다른 필드)만 봐서
		 * 이 주장은 검증된 적이 없었다.
		 *
		 * 락이 없으면 두 트랜잭션이 같은 스냅샷을 읽고 각자 UPDATE를 쏴서, 성공 응답을 받은
		 * 요청의 값이 정작 DB에 없는 상태가 만들어진다.
		 */
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		AtomicLong order = new AtomicLong();

		try {
			List<Future<Completion>> futures = List.of("관리자 A가 바꾼 이름", "관리자 B가 바꾼 이름").stream()
					.map(name -> executor.submit(() -> {
						start.await();
						eventService.updateEvent(eventId, EventUpdateRequest.builder().name(name).build());
						// 커밋이 끝난 뒤(프록시가 트랜잭션을 닫은 뒤) 순번을 찍는다.
						return new Completion(name, order.incrementAndGet());
					}))
					.toList();

			start.countDown();

			Completion first = futures.get(0).get(10, SECONDS);
			Completion second = futures.get(1).get(10, SECONDS);

			// 락이 직렬화해주므로 둘 다 성공해야 한다(하나가 거절당하는 설계가 아니다).
			String lastCommitted = first.sequence() > second.sequence() ? first.name() : second.name();

			String persisted = reloadName();
			assertThat(persisted).isNotEqualTo(ORIGINAL_NAME);
			assertThat(persisted).isEqualTo(lastCommitted);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	@DisplayName("서로 다른 필드를 동시에 수정하면 두 변경이 모두 남는다")
	void concurrentDifferentFieldUpdatesPreserveBothChanges() throws Exception {
		// 기존 테스트가 보던 케이스를 서비스 경로로 옮긴 것이다.
		LocalDateTime changedOpenAt = LocalDateTime.now().plusDays(2).withNano(0);
		LocalDateTime changedCloseAt = changedOpenAt.plusDays(3);

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);

		try {
			Future<?> nameUpdate = executor.submit(() -> {
				start.await();
				return eventService.updateEvent(eventId,
						EventUpdateRequest.builder().name("이름만 바꾼 요청").build());
			});
			Future<?> periodUpdate = executor.submit(() -> {
				start.await();
				return eventService.updateEvent(eventId,
						EventUpdateRequest.builder().openAt(changedOpenAt).closeAt(changedCloseAt).build());
			});

			start.countDown();
			nameUpdate.get(10, SECONDS);
			periodUpdate.get(10, SECONDS);
		} finally {
			executor.shutdownNow();
		}

		Event persisted = reload();
		assertThat(persisted.getName()).isEqualTo("이름만 바꾼 요청");
		assertThat(persisted.getOpenAt()).isEqualTo(changedOpenAt);
		assertThat(persisted.getCloseAt()).isEqualTo(changedCloseAt);
	}

	@Test
	@DisplayName("수정은 자기가 안 건드린 status 컬럼을 되돌리지 않는다")
	void updateEventDoesNotRewriteStatusColumn() {
		/*
		 * @DynamicUpdate가 없으면 더티체킹이 전 컬럼을 쓴다 — updateEvent가 읽어둔 낡은
		 * status를 그대로 다시 써서, 그 사이 updateStatusIfMatches(상태 변경 API·스케줄러)가
		 * 만든 값을 되돌릴 수 있다. 지금은 비관적 락이 그 창을 막지만 락이 유일한 방어선이면
		 * 읽는 경로가 하나 늘어나는 순간 되살아난다.
		 *
		 * 락 밖에서 status를 바꾼 뒤 수정 API를 태워, 낡은 값이 다시 써지지 않는지 본다.
		 */
		transactionTemplate.executeWithoutResult(status ->
				entityManager.createNativeQuery(
								"UPDATE event SET status = 'OPEN' WHERE event_id = :eventId")
						.setParameter("eventId", eventId)
						.executeUpdate());

		eventService.updateEvent(eventId, EventUpdateRequest.builder().name("상태 유지 확인").build());

		Event persisted = reload();
		assertThat(persisted.getName()).isEqualTo("상태 유지 확인");
		assertThat(persisted.getStatus().name()).isEqualTo("OPEN");
	}

	private record Completion(String name, long sequence) {
	}

	private Event reload() {
		return transactionTemplate.execute(status -> {
			entityManager.clear();
			return eventRepository.findById(eventId).orElseThrow();
		});
	}

	private String reloadName() {
		return reload().getName();
	}

	private void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, SECONDS)) {
				throw new IllegalStateException("동시성 테스트 대기 시간이 초과되었습니다.");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("동시성 테스트 대기 중 인터럽트가 발생했습니다.", e);
		}
	}
}
