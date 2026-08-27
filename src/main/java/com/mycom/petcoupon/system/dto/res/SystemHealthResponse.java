package com.mycom.petcoupon.system.dto.res;

import java.util.List;

import lombok.Builder;

// 시스템 헬스체크(#170) — overall은 Spring Boot의 StatusAggregator가 컴포넌트들로부터
// 계산한 종합 상태(하나라도 DOWN이면 전체 DOWN 등)를 그대로 담는다. Kafka는 아직 자동
// 등록되는 헬스 인디케이터가 없어 components에 포함되지 않는다(#170 참고 사항 — 별도
// HealthIndicator 구현 필요, 오늘 스코프 제외).
@Builder
public record SystemHealthResponse(
        String overallStatus,
        List<ComponentHealthResponse> components
) {
}
