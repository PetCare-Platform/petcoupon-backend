package com.mycom.petcoupon.global.common.exception;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.util.DisconnectedClientHelper;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;

import com.mycom.petcoupon.global.common.CustomResponse;
import com.mycom.petcoupon.global.common.code.BaseErrorCode;
import com.mycom.petcoupon.global.common.code.CommonErrorCode;
import com.mycom.petcoupon.monitoring.exception.MonitoringErrorCode;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
	
	/*
	 * [로그 레벨 정책 — #191]
	 *
	 * 기준은 "누가 고쳐야 하는가"다.
	 *
	 *  - 4xx는 클라이언트가 잘못 보냈고 서버는 설계대로 거절한 것이다. 예상 범위 안의 정상 동작이라
	 *    DEBUG로 남긴다. WARN으로 두면 스캐너가 없는 경로를 훑거나 프론트가 검증에 걸릴 때마다
	 *    관리자 화면이 채워져, 정작 봐야 할 장애가 묻힌다.
	 *  - 5xx는 서버가 책임져야 하는 상태다. WARN/ERROR로 남겨 관리자 화면에 올린다.
	 *
	 * MonitoringLogAppender가 WARN/ERROR만 수집하므로, 이 구분이 곧 "관리자 화면에 무엇이 뜨는가"다.
	 * 4xx도 콘솔에서 보려면 logging.level.com.mycom.petcoupon.global.common.exception=DEBUG로 켜면 된다.
	 */

	// 커스텀 예외 처리
    @ExceptionHandler(GeneralException.class)
    public ResponseEntity<CustomResponse<Void>> handleCustomException(GeneralException ex) {

    	BaseErrorCode errorCode = ex.getErrorCode();

    	/*
     * GeneralException은 4xx(쿠폰 소진·중복 발급 같은 업무 거절)와 5xx를 모두 싣는다. 다만
     * 스트림 연결 한도 초과는 서버 장애가 아니라 예상 가능한 capacity rejection이다. 503 응답은
     * 재시도를 유도하려고 유지하되, INFO로 분리해 MonitoringLogAppender(WARN/ERROR 수집)에
     * 재유입되지 않게 한다. Redis·DB 장애 등 나머지 5xx는 계속 ERROR다.
     */
        if (errorCode == MonitoringErrorCode.TOO_MANY_STREAM_CONNECTIONS) {
            log.info("[CustomException] {} {}", errorCode.getCode(), errorCode.getMessage());
        } else if (errorCode.getStatus().is5xxServerError()) {
            log.error("[CustomException] {} {}", errorCode.getCode(), errorCode.getMessage());
        } else {
            log.debug("[CustomException] {} {}", errorCode.getCode(), errorCode.getMessage());
        }

        return jsonError(errorCode);
    }

    // @Valid 검증 예외 처리
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<CustomResponse<Map<String, String>>> handleValidationException(MethodArgumentNotValidException ex) {

        Map<String, String> errors = new HashMap<>();
        
        ex.getBindingResult()
        		.getFieldErrors()
        		.forEach(error ->
        			errors.putIfAbsent(
        					error.getField(), 
        					error.getDefaultMessage()
        			)
        );

        BaseErrorCode errorCode = CommonErrorCode.NOT_VALID_ERROR;
        
        CustomResponse<Map<String, String>> errorResponse = CustomResponse.onFailure(
        		errorCode.getCode(),
        		errorCode.getMessage(),
                errors
        );
        
        return jsonError(errorCode, errorResponse);
    }

    // @PathVariable / @RequestParam 검증 예외 처리 (컨트롤러 클래스에 @Validated 필요)
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<CustomResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {

        BaseErrorCode errorCode = CommonErrorCode.NOT_VALID_ERROR;

        log.debug("[ConstraintViolation] {}", ex.getMessage());

        return jsonError(errorCode);
    }

    // 필수 요청 헤더 누락 (예: 쿠폰 신청 API의 Idempotency-Key, 이슈 #16) — 없으면 400으로 응답
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<CustomResponse<Void>> handleMissingHeader(MissingRequestHeaderException ex) {

        BaseErrorCode errorCode = CommonErrorCode.NOT_VALID_ERROR;

        log.debug("[MissingHeader] {}", ex.getMessage());

        return jsonError(errorCode);
    }

    // 쿼리 파라미터·경로 변수 타입 변환 실패 (예: status=INVALID처럼 enum에 없는 값)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<CustomResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {

        BaseErrorCode errorCode = CommonErrorCode.NOT_VALID_ERROR;

        log.debug("[TypeMismatch] {}", ex.getMessage());

        return jsonError(errorCode);
    }

    /*
     * 아래 두 핸들러는 원래 아무 로그도 남기지 않았다. 관리자 화면에 보였던 건
     * ExceptionHandlerExceptionResolver가 해결된 예외마다 남기는 "Resolved [...]" WARN 덕분이었는데,
     * 그 로거를 수집 대상에서 빼면서(#191) 그 경로가 사라졌다. 다른 핸들러와 형식을 맞춰 직접
     * 남기되, 둘 다 400·405라 위 정책대로 DEBUG다.
     */

    // 잘못된 JSON 요청
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CustomResponse<Void>> handleInvalidJson(HttpMessageNotReadableException ex) {

        BaseErrorCode errorCode = CommonErrorCode.INVALID_JSON;

        log.debug("[InvalidJson] {}", ex.getMessage());

        return jsonError(errorCode);
    }

    // 지원하지 않는 HTTP 메서드
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<CustomResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {

    	BaseErrorCode errorCode = CommonErrorCode.METHOD_NOT_ALLOWED;

    	log.debug("[MethodNotAllowed] {}", ex.getMessage());

    	return jsonError(errorCode);
    }
    
    // 매칭되는 핸들러가 없는 경로
    // 이 핸들러가 없으면 아래 catch-all(Exception)이 대신 잡아 500으로 나간다 —
    // NoResourceFoundException도 Exception의 하위라서다. 그러면 오타 URL과 서버 장애가
    // 응답으로 구분되지 않는다.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<CustomResponse<Void>> handleNoResourceFound(NoResourceFoundException ex) {

    	// 스택 트레이스를 남기지 않는다. 잘못된 주소로 들어온 요청이라 서버 결함이 아니고,
    	// 스캐너가 없는 경로를 훑으면 로그가 그것만으로 가득 찬다. 같은 이유로 DEBUG다 —
    	// 실제로 /actuator/prometheus 폴링 하나가 2초마다 이 WARN을 찍고 있었다.
    	log.debug("[NoResourceFound] 매칭되는 핸들러 없음: {} {}", ex.getHttpMethod(), ex.getResourcePath());

    	BaseErrorCode errorCode = CommonErrorCode.NOT_FOUND;

    	return jsonError(errorCode);
    }

    /*
     * 비동기 응답(SSE)이 더 이상 쓸 수 없게 된 뒤의 write 시도 (#191).
     *
     * 관리자 모니터링 스트림(/admin/monitoring/stream)에서 클라이언트가 연결을 끊으면
     * Spring이 응답 래퍼를 NOT_USABLE 상태로 바꾸고, 이후의 write·flush는 전부 이 예외가 된다.
     * 그 예외는 async dispatch를 타고 여기까지 올라온다.
     *
     * 이걸 catch-all이 잡으면 두 가지가 연쇄로 터진다.
     *  1. 클라이언트가 탭을 닫은 것뿐인데 ERROR + 스택 트레이스가 남는다. 서버 장애로 보인다.
     *  2. 그 핸들러가 JSON 본문을 쓰려다 같은 이유로 실패해 HttpMessageNotWritableException이
     *     나고, ExceptionHandlerExceptionResolver가 "Failure in @ExceptionHandler"를 WARN으로 남긴다.
     * 재연결이 잦은 SSE에서는 이 쌍이 계속 반복된다.
     *
     * 그래서 본문을 쓰지 않는다. null을 반환하면 HttpEntityMethodProcessor가 요청을 처리 완료로
     * 표시하고 아무것도 쓰지 않는다 — 어차피 쓸 수 없는 응답이라 이게 유일하게 맞는 동작이다.
     * 로그도 DEBUG로 낮춘다. 그래야 MonitoringLogAppender(WARN/ERROR만 수집)에 다시 걸려
     * "SSE 오류가 모니터링 이벤트를 만들고 그게 다시 SSE 오류를 부르는" 피드백 루프가 생기지 않는다.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public ResponseEntity<CustomResponse<Void>> handleAsyncRequestNotUsable(AsyncRequestNotUsableException ex) {

        log.debug("[ClientDisconnected] 비동기 응답에 더 이상 쓸 수 없다: {}", ex.getMessage());

        return null;
    }

    /*
     * 비동기 요청이 제한 시간을 넘긴 경우. SSE는 emitter-timeout(기본 30분)마다 반드시 여기를 지난다.
     *
     * 이미 스트리밍을 시작한 응답은 커밋된 상태라 JSON 본문을 덧붙일 수 없다. 억지로 쓰면 SSE
     * 프레임 뒤에 JSON이 붙거나 위와 같은 write 실패 연쇄가 난다. 정상적인 수명 종료이므로
     * 조용히 끝낸다.
     *
     * 아직 커밋되지 않은 비동기 요청(스트리밍이 아닌 async 엔드포인트)은 원래대로 503을 준다 —
     * 클라이언트가 재시도할 수 있어야 한다.
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<CustomResponse<Void>> handleAsyncRequestTimeout(
            AsyncRequestTimeoutException ex,
            HttpServletResponse response
    ) {

        if (response.isCommitted()) {
            log.debug("[AsyncTimeout] 이미 커밋된 비동기 응답이 만료됐다: {}", ex.getMessage());
            return null;
        }

        log.warn("[AsyncTimeout] 비동기 요청이 제한 시간을 넘겼다: {}", ex.getMessage());

        return jsonError(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    // 처리하지 않은 모든 예외 처리
    @ExceptionHandler(Exception.class)
    public ResponseEntity<CustomResponse<Void>> handleAllException(Exception ex) {

        /*
         * 클라이언트가 먼저 끊은 경우는 서버 결함이 아니다. Tomcat ClientAbortException,
         * EOFException, "broken pipe"/"connection reset by peer" 같은 것들이 여기 해당한다.
         * 위 AsyncRequestNotUsableException은 전용 핸들러가 잡지만, 같은 원인이 다른 타입으로
         * 올라오는 경로가 남아 있어 catch-all에도 같은 판정을 둔다.
         *
         * 판정은 Spring의 DisconnectedClientHelper에 맡긴다. 예외 타입 이름과 메시지 문구
         * 목록을 직접 들고 있으면 컨테이너를 바꿀 때마다 어긋난다.
         */
        if (DisconnectedClientHelper.isClientDisconnectedException(ex)) {
            log.debug("[ClientDisconnected] 클라이언트가 먼저 연결을 끊었다: {}", ex.getMessage());
            return null;
        }

        log.error("[Unhandled Exception]", ex);

        BaseErrorCode errorCode = CommonErrorCode.INTERNAL_SERVER_ERROR;

        CustomResponse<Void> errorResponse = CustomResponse.onFailure(
        		errorCode.getCode(),
        		errorCode.getMessage()
        );

        return jsonError(errorCode, errorResponse);
    }

    private ResponseEntity<CustomResponse<Void>> jsonError(BaseErrorCode errorCode) {
        return jsonError(errorCode, errorCode.getErrorResponse());
    }

    /*
     * 모든 오류 응답이 이 한 곳을 거친다. Content-Type을 여기서만 박아두려는 것이다.
     *
     * 명시하지 않으면 Spring이 요청의 Accept 헤더로 협상을 시도한다. 대부분의 클라이언트는 JSON을
     * 받아들이니 문제가 없지만, SSE 스트림(/admin/monitoring/stream)을 부르는 fetch 기반 클라이언트는
     * Accept: text/event-stream만 보낸다. 그러면 JSON 본문을 쓸 수 있는 converter를 찾지 못해
     * HttpMediaTypeNotAcceptableException이 나고, ExceptionHandlerExceptionResolver는 그때
     * 원래 예외를 그대로 다시 던진다 — 401/404/405가 전부 500으로 뭉개진다.
     *
     * 이건 GeneralException만의 문제가 아니다. 그 엔드포인트에 경로 오타(NoResourceFound),
     * 잘못된 메서드(HttpRequestMethodNotSupported), 예기치 못한 예외(catch-all)가 나도 똑같이
     * 재현된다. 특히 catch-all은 "무슨 일이 나든 깔끔한 JSON 500을 준다"는 안전망인데 같은 이유로
     * 실패할 수 있어서, 핸들러 하나씩 고치는 대신 응답 생성 지점을 여기로 모았다.
     *
     * 오류 응답은 협상 대상이 아니다. 클라이언트가 뭘 받아들이든 서버는 무슨 일이 났는지 말해야 한다.
     */
    private <T> ResponseEntity<CustomResponse<T>> jsonError(BaseErrorCode errorCode, CustomResponse<T> body) {
        return ResponseEntity
                .status(errorCode.getStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
