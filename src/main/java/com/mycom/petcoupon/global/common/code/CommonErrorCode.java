package com.mycom.petcoupon.global.common.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum CommonErrorCode implements BaseErrorCode {
	
	BAD_REQUEST(HttpStatus.BAD_REQUEST, "COMMON400-0", "잘못된 요청입니다."),
	
    NOT_VALID_ERROR(HttpStatus.BAD_REQUEST, "COMMON400-1", "요청 값이 올바르지 않습니다."),
    
    INVALID_JSON(HttpStatus.BAD_REQUEST, "COMMON400-2", "요청 JSON 형식이 올바르지 않습니다."),
  
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON401-0", "인증이 필요합니다."),

    FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON403-0", "접근 권한이 없습니다."),

    NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON404-0", "요청한 리소스를 찾을 수 없습니다."),
    
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON405-0", "지원하지 않는 HTTP 메서드입니다."),

    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON500-0", "서버 내부 오류가 발생했습니다.");
	
	private final HttpStatus status;
	private final String code;
	private final String message;
}
