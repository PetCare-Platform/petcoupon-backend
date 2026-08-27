package com.mycom.petcoupon.system.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

// 시스템 컴포넌트 상태 조회(#170) — 자체 /actuator/health를 HTTP로 재호출하지 않고
// HealthEndpoint를 직접 주입받아 쓴다(왕복·직렬화 비용 없이 바로 접근 가능).
//
// Spring Boot 4.x부터 헬스 관련 클래스가 spring-boot-actuator가 아니라 별도 모듈
// (spring-boot-health, org.springframework.boot.health.*)로 옮겨졌다 — 예전 버전의
// org.springframework.boot.actuate.health.HealthComponent/CompositeHealth API와는
// 패키지·클래스명이 다르다.
//
// HealthEndpoint.health()는 여러 컴포넌트(db/redis/kafka 등)가 있으면 CompositeHealthDescriptor를,
// 컴포넌트가 하나뿐이면(드묾) 그냥 단일 HealthDescriptor를 돌려준다 — 둘 다 방어적으로 처리한다.
@Service
@RequiredArgsConstructor
public class SystemHealthService {

    private final HealthEndpoint healthEndpoint;

    // overall은 CompositeHealthDescriptor.getStatus()가 이미 StatusAggregator로 계산해서
    // 들고 있는 값을 그대로 쓴다 — 컴포넌트별 상태로부터 우리가 다시 집계 로직을 만들 필요가
    // 없다(예: 하나라도 DOWN이면 전체 DOWN 같은 규칙을 직접 구현하면 프레임워크 기본 정책과
    // 어긋날 수 있음).
    //
    // getComponents()의 각 값을 리프(leaf) 인디케이터로 가정하고 1단만 순회한다 — 지금
    // (db/redis/디스크/ssl 등) 실측 결과가 평평한 구조라 문제없다. 나중에
    // management.endpoint.health.group.* 설정으로 그룹을 묶으면 컴포넌트 하나가 또 다른
    // CompositeHealthDescriptor(중첩 그룹)가 될 수 있는데, 그러면 이 메서드는 그 그룹의
    // 집계 상태만 보이고 안의 개별 인디케이터는 안 보인다 — 재귀 순회가 아니라 알고 있는
    // 제약이다.
    public SystemHealthSnapshot getSnapshot() {
        HealthDescriptor descriptor = healthEndpoint.health();
        Map<String, String> componentStatuses = new LinkedHashMap<>();

        if (descriptor instanceof CompositeHealthDescriptor composite) {
            composite.getComponents()
                    .forEach((name, component) -> componentStatuses.put(name, component.getStatus().getCode()));
        } else {
            componentStatuses.put("application", descriptor.getStatus().getCode());
        }

        return new SystemHealthSnapshot(descriptor.getStatus().getCode(), componentStatuses);
    }

    public record SystemHealthSnapshot(String overallStatus, Map<String, String> componentStatuses) {
    }
}
