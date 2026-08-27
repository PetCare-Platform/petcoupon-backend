package com.mycom.petcoupon.event.exception;

import org.springframework.http.HttpStatus;

import com.mycom.petcoupon.global.common.code.BaseErrorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum EventErrorCode implements BaseErrorCode {

	INVALID_EVENT_PERIOD(HttpStatus.BAD_REQUEST, "EVENT400-0", "이벤트 기간이 올바르지 않습니다."),

	SAME_EVENT_STATUS(HttpStatus.BAD_REQUEST, "EVENT400-1", "현재 상태와 동일합니다."),

	INVALID_EVENT_STATUS_TRANSITION(HttpStatus.BAD_REQUEST, "EVENT400-2", "상태는 SCHEDULED→OPEN, OPEN→CLOSED 순서로만 변경할 수 있습니다."),

	INVALID_EVENT_PAGE_REQUEST(HttpStatus.BAD_REQUEST, "EVENT400-3", "페이지 번호는 0 이상 10000 이하이며, 페이지 크기는 10, 20, 50, 100 중 하나여야 합니다."),

	EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "EVENT404-0", "존재하지 않는 이벤트입니다."),

	// 공개 이벤트 상세(GET /events/{eventId})에서 OPEN이 아닌 이벤트에 접근한 경우.
	// 일반 사용자에겐 SCHEDULED(미공개)·CLOSED(종료) 모두 "지금 볼 수 있는 이벤트가 아니다"로
	// 수렴해야 하고, 상태를 그대로 알려주면 아직 공개하지 않은 이벤트의 존재가 드러나므로
	// EVENT_NOT_FOUND와 같은 404로 응답하되, 원인 구분을 위해 코드만 분리한다.
	EVENT_NOT_OPEN(HttpStatus.NOT_FOUND, "EVENT404-1", "공개 상세 조회할 수 있는 이벤트가 아닙니다."),

	EVENT_STATUS_CONFLICT(HttpStatus.CONFLICT, "EVENT409-0", "다른 요청에 의해 이벤트 상태가 이미 변경되었습니다."),

	EVENT_LIST_QUERY_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "EVENT500-0", "이벤트 목록 조회에 실패했습니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
