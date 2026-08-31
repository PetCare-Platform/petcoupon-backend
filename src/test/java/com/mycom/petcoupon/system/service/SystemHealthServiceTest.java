package com.mycom.petcoupon.system.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * HealthEndpoint가 실제로 어떤 컴포넌트 이름·상태를 돌려주는지 확인한다 — Spring Boot
 * 버전마다 자동 등록되는 인디케이터가 달라서(#170 구현 중 실측 필요) 추측 대신 실제 앱
 * 컨텍스트로 확인한다.
 *
 * 실행 전 MySQL/Redis/Kafka가 떠 있어야 한다: docker compose up -d
 */
@SpringBootTest
class SystemHealthServiceTest {

    @Autowired
    private SystemHealthService systemHealthService;

    @Test
    void getSnapshot은_전체_상태와_컴포넌트별_상태를_반환한다() {
        SystemHealthService.SystemHealthSnapshot snapshot = systemHealthService.getSnapshot();
        Map<String, String> statuses = snapshot.componentStatuses();

        assertThat(snapshot.overallStatus()).isNotBlank();
        assertThat(statuses).isNotEmpty();
        // 모든 값이 실제 상태 코드(UP/DOWN/OUT_OF_SERVICE/UNKNOWN)인지 — null/빈 문자열이
        // 섞이지 않는지 확인
        assertThat(statuses.values()).allSatisfy(status -> assertThat(status).isNotBlank());
        // [PR 리뷰 반영] isNotEmpty()만으로는 컴포넌트가 1개만 남아도 통과한다 — getSnapshot()의
        // 순회 로직이 나중에 깨져도 못 잡는다. 이 프로젝트가 명시적으로 의존하는 DataSource·
        // RedisConnectionFactory가 있는 한 계속 나오는 db·redis 두 개만 고정한다. diskSpace·ssl은
        // Spring Boot 버전에 따라 자동 등록 여부가 바뀔 수 있어 제외한다.
        assertThat(statuses).containsKeys("db", "redis");

        // 이 assert는 실측 확인용 — 실패하면 로그로 실제 컴포넌트 이름을 보여준다.
        System.out.println("[SystemHealthServiceTest] overall=" + snapshot.overallStatus() + ", 컴포넌트: " + statuses);
    }
}
