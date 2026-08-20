package com.mycom.petcoupon.global.common.exception;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
        
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(errorCode.getErrorResponse());
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
        
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(errorResponse);
    }

    // @PathVariable / @RequestParam 검증 예외 처리 (컨트롤러 클래스에 @Validated 필요)
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<CustomResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {

        BaseErrorCode errorCode = CommonErrorCode.NOT_VALID_ERROR;

        log.warn("[ConstraintViolation] {}", ex.getMessage());

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(errorCode.getErrorResponse());
    }

    // 잘못된 JSON 요청
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CustomResponse<Void>> handleInvalidJson(HttpMessageNotReadableException ex) {

        BaseErrorCode errorCode = CommonErrorCode.INVALID_JSON;

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(errorCode.getErrorResponse());
    }
    
    // 지원하지 않는 HTTP 메서드
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<CustomResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {

    	BaseErrorCode errorCode = CommonErrorCode.METHOD_NOT_ALLOWED;
    	
    	return ResponseEntity
                .status(errorCode.getStatus())
                .body(errorCode.getErrorResponse());
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
        
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(errorResponse);
    }
}
