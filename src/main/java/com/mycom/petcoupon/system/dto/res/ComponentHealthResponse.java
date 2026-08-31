package com.mycom.petcoupon.system.dto.res;

import lombok.Builder;

// 시스템 헬스체크(#170) — 컴포넌트 하나의 상태 (예: db=UP, redis=UP).
@Builder
public record ComponentHealthResponse(
        String name,
        String status
) {
}
