package com.mycom.petcoupon.messaging.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;

@ExtendWith(MockitoExtension.class)
class CouponIssueReprocessRecoverySchedulerTest {

	@Mock
	private IssueMessageRepository issueMessageRepository;

	@InjectMocks
	private CouponIssueReprocessRecoveryScheduler scheduler;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(scheduler, "staleAfterMs", 300_000L);
	}

	@Test
	void staleAfterMs만큼_지난_cutoff로_복구를_요청한다() {
		when(issueMessageRepository.recoverStaleReprocessingMessages(any(LocalDateTime.class), anyString()))
				.thenReturn(2);

		scheduler.recoverStaleReprocessingMessages();

		ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
		verify(issueMessageRepository).recoverStaleReprocessingMessages(cutoffCaptor.capture(), anyString());

		LocalDateTime expectedCutoff = LocalDateTime.now().minusMinutes(5);
		assertThat(cutoffCaptor.getValue()).isCloseTo(expectedCutoff, within(3, ChronoUnit.SECONDS));
	}

	// 이번 주기에 DB 예외가 나도 스케줄러 자체는 안 죽어야 다음 주기에 다시 시도할 수 있다.
	@Test
	void repository가_예외를_던져도_전파하지_않는다() {
		when(issueMessageRepository.recoverStaleReprocessingMessages(any(LocalDateTime.class), anyString()))
				.thenThrow(new RuntimeException("DB 오류"));

		assertThatCode(() -> scheduler.recoverStaleReprocessingMessages()).doesNotThrowAnyException();
	}
}
