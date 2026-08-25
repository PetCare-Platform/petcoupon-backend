package com.mycom.petcoupon.event.dto.req;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

import lombok.Builder;

/**
 * 이벤트 단순 필드 부분 수정 요청.
 *
 * 부분 수정 규칙
 * - 요청에 없거나 null인 필드는 변경하지 않는다.
 * - 최소 한 개 필드는 포함해야 한다.
 * - description을 비우려면 빈 문자열("")을 보낸다. (null은 "변경 없음"이므로 비우기에 쓸 수 없다)
 * - openAt / closeAt은 하나만 보낼 수 있으며, 이 경우 기존 값과 합쳐 기간 유효성을 검증한다.
 */
@Builder
public record EventUpdateRequest(
		@Size(max = 100, message = "이벤트 이름은 100자 이하여야 합니다.")
		String name,

		// ""이면 설명을 비운다
		@Size(max = 500, message = "이벤트 설명은 500자 이하여야 합니다.")
		String description,

		LocalDateTime openAt,

		LocalDateTime closeAt
) {

	@JsonIgnore
	@AssertTrue(message = "수정할 항목을 최소 하나 이상 포함해야 합니다.")
	public boolean isAnyFieldPresent() {
		return name != null || description != null || openAt != null || closeAt != null;
	}

	@JsonIgnore
	@AssertTrue(message = "이벤트 이름은 공백일 수 없습니다.")
	public boolean isNameNotBlank() {
		return name == null || !name.isBlank();
	}
}
