package com.mycom.petcoupon.reconciliation.batch.config;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import com.zaxxer.hikari.HikariDataSource;

@SpringBootTest(properties = {
        "event.status.scheduler.enabled=false",
        "coupon.status.enabled=false",
        // spring.datasource.hikari.maximum-pool-size는 기본값이 ${DB_POOL_SIZE:10}이라, 부하
        // 테스트 등으로 DB_POOL_SIZE 환경변수를 다르게(예: 100) 띄운 환경에서 이 테스트만 돌리면
        // 아래 10 하드코딩 검증이 깨진다 — 이 테스트가 보려는 건 "두 풀이 서로 다른 크기로
        // 분리되어 있다"는 것 자체이지 실제 운영 풀 크기가 아니므로, 환경변수와 무관하게 10으로
        // 고정한다.
        "spring.datasource.hikari.maximum-pool-size=10"
})
class DataSourceWiringVerificationTest {

    @Autowired
    private DataSource defaultDataSource;

    @Autowired
    @Qualifier("reconciliationLockDataSource")
    private DataSource lockDataSource;

    @Test
    void 기본_DataSource와_락_전용_DataSource는_서로_다른_풀이다() {
        assertThat(defaultDataSource).isNotSameAs(lockDataSource);

        HikariDataSource defaultHikari = (HikariDataSource) defaultDataSource;
        HikariDataSource lockHikari = (HikariDataSource) lockDataSource;

        // 실패했을 때만 두 풀의 이름·크기가 로그에 찍히면 되므로 System.out.println 대신
        // .as()에 실어둔다 — 통과하면 조용하고, 실패하면 원인 파악에 필요한 값이 바로 보인다.
        String context = "defaultDataSource pool name=%s maxPoolSize=%d, lockDataSource pool name=%s maxPoolSize=%d"
                .formatted(defaultHikari.getPoolName(), defaultHikari.getMaximumPoolSize(),
                        lockHikari.getPoolName(), lockHikari.getMaximumPoolSize());

        assertThat(defaultHikari.getPoolName()).as(context).isNotEqualTo(lockHikari.getPoolName());
        assertThat(defaultHikari.getMaximumPoolSize()).as(context).isEqualTo(10);
        assertThat(lockHikari.getMaximumPoolSize()).as(context).isEqualTo(3);
    }
}
