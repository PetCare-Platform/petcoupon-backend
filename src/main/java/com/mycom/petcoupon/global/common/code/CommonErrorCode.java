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

    // 행 락 경합. 서버 결함이 아니라 "지금 다른 요청이 잡고 있다"는 상태라 500이 아니다.
    // 재시도하면 대개 바로 성공하므로 안내 문구에 재시도를 명시한다.
    LOCK_CONFLICT(HttpStatus.CONFLICT, "COMMON409-0", "다른 요청이 같은 데이터를 수정하고 있습니다. 잠시 후 다시 시도해 주세요."),

    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON500-0", "서버 내부 오류가 발생했습니다."),

    // 서버 결함이 아니라 "지금은 못 한다"는 상태다. 500으로 주면 프론트가 재시도를 포기한다.
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "COMMON503-0", "일시적으로 요청을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.");
	
	private final HttpStatus status;
	private final String code;
	private final String message;
}
