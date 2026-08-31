package com.mycom.petcoupon.event.repository;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.repository.AppUserRepository;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EventPessimisticLockIntegrationTest {

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
					.name("비관적 락 테스트 사용자")
					.email("event-pessimistic-lock-" + UUID.randomUUID() + "@test.com")
					.phone("010-1234-5678")
					.build());

			LocalDateTime now = LocalDateTime.now();
			Event event = eventRepository.save(Event.builder()
					.createdBy(user)
					.name("원래 이벤트")
					.description("원래 설명")
					.openAt(now.minusHours(1))
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
				eventRepository.deleteById(eventId);
			}
			if (userId != null) {
				appUserRepository.deleteById(userId);
			}
		});
	}

	@Test
	void concurrentUpdatesWaitForRowLockAndPreserveBothChanges() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch firstRequestLocked = new CountDownLatch(1);
		CountDownLatch releaseFirstRequest = new CountDownLatch(1);
		CountDownLatch secondRequestStarted = new CountDownLatch(1);
		LocalDateTime changedOpenAt = LocalDateTime.of(2026, 8, 31, 13, 30);
		LocalDateTime changedCloseAt = LocalDateTime.of(2026, 9, 2, 14, 0);

		try {
			Future<?> firstRequest = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
				Event event = eventRepository.findByIdForUpdate(eventId).orElseThrow();
				event.updateName("관리자 A가 바꾼 이름");
				firstRequestLocked.countDown();
				await(releaseFirstRequest);
			}));

			await(firstRequestLocked);
			Future<String> secondRequest = executor.submit(() -> transactionTemplate.execute(status -> {
				secondRequestStarted.countDown();
				Event event = eventRepository.findByIdForUpdate(eventId).orElseThrow();
				event.updatePeriod(changedOpenAt, changedCloseAt);
				return event.getName();
			}));

			await(secondRequestStarted);
			assertThatThrownBy(() -> secondRequest.get(300, MILLISECONDS))
					.isInstanceOf(TimeoutException.class);

			releaseFirstRequest.countDown();
			firstRequest.get(3, SECONDS);
			assertThat(secondRequest.get(3, SECONDS)).isEqualTo("관리자 A가 바꾼 이름");

			Event persistedEvent = eventRepository.findById(eventId).orElseThrow();
			assertThat(persistedEvent.getName()).isEqualTo("관리자 A가 바꾼 이름");
			assertThat(persistedEvent.getOpenAt()).isEqualTo(changedOpenAt);
			assertThat(persistedEvent.getCloseAt()).isEqualTo(changedCloseAt);
		} finally {
			releaseFirstRequest.countDown();
			executor.shutdownNow();
		}
	}

	private void await(CountDownLatch latch) {
		try {
			if (!latch.await(3, SECONDS)) {
				throw new IllegalStateException("동시성 테스트 대기 시간이 초과되었습니다.");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("동시성 테스트 대기 중 인터럽트가 발생했습니다.", e);
		}
	}
}
