package com.mycom.petcoupon.global.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.mycom.petcoupon.global.common.code.CommonErrorCode;
import com.mycom.petcoupon.monitoring.exception.MonitoringErrorCode;

/**
 * 오류 로그 레벨 정책을 지킨다(#191).
 *
 * <p>기준은 "누가 고쳐야 하는가"다. 4xx는 클라이언트가 잘못 보냈고 서버는 설계대로 거절한
 * 것이므로 DEBUG, 서버 장애 5xx는 WARN/ERROR다. 단, 예상 가능한 스트림 연결 한도 초과는
 * 503 응답을 유지하면서 INFO로 남긴다.
 *
 * <p>이게 단순한 취향 문제가 아닌 이유는 {@code MonitoringLogAppender}가 WARN/ERROR만
 * 수집하기 때문이다. 즉 이 정책이 곧 <b>관리자 실시간 화면에 무엇이 뜨는가</b>를 정한다.
 * 4xx가 WARN이면 스캐너의 경로 훑기나 프론트의 검증 실패가 화면을 채워 진짜 장애를 묻는다.
 * 반대로 5xx가 DEBUG로 새면 장애를 놓친다.
 */
class GlobalExceptionHandlerLogLevelTest {

	private static final String LOGGER_NAME = GlobalExceptionHandler.class.getName();

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	private Logger logger;
	private Level originalLevel;

	@BeforeEach
	void setUp() {
		logger = (Logger) LoggerFactory.getLogger(LOGGER_NAME);
		originalLevel = logger.getLevel();

		// DEBUG로 내려야 "DEBUG로 남겼다"와 "아예 안 남겼다"를 구분할 수 있다.
		logger.setLevel(Level.DEBUG);
		appender.start();
		logger.addAppender(appender);
	}

	@AfterEach
	void tearDown() {
		logger.detachAppender(appender);
		appender.stop();
		logger.setLevel(originalLevel);
	}

	@Test
	@DisplayName("4xx 업무 예외는 DEBUG로 남긴다")
	void logsClientSideBusinessErrorsAtDebug() {
		// 쿠폰 소진·중복 발급 같은 정상적인 거절이다. 관리자 화면에 뜰 일이 아니다.
		handler.handleCustomException(new GeneralException(CommonErrorCode.NOT_FOUND));

		assertThat(levels()).containsExactly(Level.DEBUG);
	}

	@Test
	@DisplayName("스트림 연결 한도 초과는 503을 유지하고 INFO로 남긴다")
	void logsExpectedStreamCapacityRejectionAtInfo() {
		/*
		 * 이 503은 장애가 아니라 예상한 capacity rejection이다. WARN/ERROR면
		 * MonitoringLogAppender가 기존 구독자에게 이 이벤트를 다시 전파해 재연결 부하를 키운다.
		 */
		handler.handleCustomException(new GeneralException(MonitoringErrorCode.TOO_MANY_STREAM_CONNECTIONS));

		assertThat(levels()).containsExactly(Level.INFO);
	}

	@Test
	@DisplayName("연결 한도 초과가 아닌 503 업무 예외는 ERROR로 남긴다")
	void logsOtherServerSideBusinessErrorsAtError() {
		// Redis·DB 장애 등 일반적인 일시적 서버 실패는 계속 관리자 모니터링 대상이어야 한다.
		handler.handleCustomException(new GeneralException(CommonErrorCode.SERVICE_UNAVAILABLE));

		assertThat(levels()).containsExactly(Level.ERROR);
	}

	@Test
	@DisplayName("없는 경로·잘못된 메서드는 DEBUG로 남긴다")
	void logsRoutingMistakesAtDebug() {
		// 실측: /actuator/prometheus 폴링 하나가 2초마다 이 로그를 찍고 있었다.
		handler.handleNoResourceFound(
				new NoResourceFoundException(HttpMethod.GET, "/actuator/prometheus", "actuator/prometheus"));
		handler.handleMethodNotSupported(new HttpRequestMethodNotSupportedException("DELETE"));

		assertThat(levels()).containsExactly(Level.DEBUG, Level.DEBUG);
	}

	@Test
	@DisplayName("클라이언트 연결 종료는 DEBUG로 남긴다")
	void logsClientDisconnectAtDebug() {
		// SSE 탭을 닫은 것뿐이라 서버 장애가 아니다. WARN/ERROR면 모니터링 피드백 루프가 된다.
		handler.handleAsyncRequestNotUsable(new AsyncRequestNotUsableException("Response not usable"));
		handler.handleAllException(new IOException("Broken pipe"));

		assertThat(levels()).containsExactly(Level.DEBUG, Level.DEBUG);
	}

	@Test
	@DisplayName("커밋된 비동기 응답의 타임아웃은 DEBUG, 아직 응답 가능하면 WARN이다")
	void splitsAsyncTimeoutByCommitState() {
		MockHttpServletResponse committed = new MockHttpServletResponse();
		committed.setCommitted(true);
		handler.handleAsyncRequestTimeout(new AsyncRequestTimeoutException(), committed);

		assertThat(levels()).containsExactly(Level.DEBUG);

		appender.list.clear();

		// 아직 아무것도 안 쓴 async 요청이 만료된 건 클라이언트가 응답을 못 받는 상황이다.
		handler.handleAsyncRequestTimeout(new AsyncRequestTimeoutException(), new MockHttpServletResponse());

		assertThat(levels()).containsExactly(Level.WARN);
	}

	@Test
	@DisplayName("진짜 서버 장애는 ERROR로 남긴다")
	void logsUnexpectedFailuresAtError() {
		// 연결 끊김을 걸러내느라 진짜 장애까지 조용해지면 안 된다.
		handler.handleAllException(new IllegalStateException("진짜 장애"));

		assertThat(levels()).containsExactly(Level.ERROR);
	}

	private List<Level> levels() {
		return appender.list.stream().map(ILoggingEvent::getLevel).toList();
	}
}
