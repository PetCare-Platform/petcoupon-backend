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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolationException;

import com.mycom.petcoupon.global.common.CustomResponse;
import com.mycom.petcoupon.global.common.code.BaseErrorCode;
import com.mycom.petcoupon.global.common.code.CommonErrorCode;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
	
	// 커스텀 예외 처리
    @ExceptionHandler(GeneralException.class)
    public ResponseEntity<CustomResponse<Void>> handleCustomException(GeneralException ex) {

    	BaseErrorCode errorCode = ex.getErrorCode();

    	log.warn("[CustomException] {}", errorCode.getMessage());

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

        log.warn("[ConstraintViolation] {}", ex.getMessage());

        return jsonError(errorCode);
    }

    // 필수 요청 헤더 누락 (예: 쿠폰 신청 API의 Idempotency-Key, 이슈 #16) — 없으면 400으로 응답
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<CustomResponse<Void>> handleMissingHeader(MissingRequestHeaderException ex) {

        BaseErrorCode errorCode = CommonErrorCode.NOT_VALID_ERROR;

        log.warn("[MissingHeader] {}", ex.getMessage());

        return jsonError(errorCode);
    }

    // 쿼리 파라미터·경로 변수 타입 변환 실패 (예: status=INVALID처럼 enum에 없는 값)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<CustomResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {

        BaseErrorCode errorCode = CommonErrorCode.NOT_VALID_ERROR;

        log.warn("[TypeMismatch] {}", ex.getMessage());

        return jsonError(errorCode);
    }

    // 잘못된 JSON 요청
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CustomResponse<Void>> handleInvalidJson(HttpMessageNotReadableException ex) {

        BaseErrorCode errorCode = CommonErrorCode.INVALID_JSON;

        return jsonError(errorCode);
    }
    
    // 지원하지 않는 HTTP 메서드
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<CustomResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {

    	BaseErrorCode errorCode = CommonErrorCode.METHOD_NOT_ALLOWED;
    	
    	return jsonError(errorCode);
    }
    
    // 매칭되는 핸들러가 없는 경로
    // 이 핸들러가 없으면 아래 catch-all(Exception)이 대신 잡아 500으로 나간다 —
    // NoResourceFoundException도 Exception의 하위라서다. 그러면 오타 URL과 서버 장애가
    // 응답으로 구분되지 않는다.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<CustomResponse<Void>> handleNoResourceFound(NoResourceFoundException ex) {

    	// 스택 트레이스를 남기지 않는다. 잘못된 주소로 들어온 요청이라 서버 결함이 아니고,
    	// 스캐너가 없는 경로를 훑으면 로그가 그것만으로 가득 찬다.
    	log.warn("[NoResourceFound] 매칭되는 핸들러 없음: {} {}", ex.getHttpMethod(), ex.getResourcePath());

    	BaseErrorCode errorCode = CommonErrorCode.NOT_FOUND;

    	return jsonError(errorCode);
    }

    // 처리하지 않은 모든 예외 처리
    @ExceptionHandler(Exception.class)
    public ResponseEntity<CustomResponse<Void>> handleAllException(Exception ex) {

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
