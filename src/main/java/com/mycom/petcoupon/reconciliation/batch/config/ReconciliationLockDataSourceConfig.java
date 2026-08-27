package com.mycom.petcoupon.reconciliation.batch.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * ReconciliationJobTriggerService가 MySQL 세션 락(GET_LOCK)을 잡는 동안 커넥션을 배치가
 * 끝날 때까지(수 분 걸릴 수 있음, 300만 건 규모 쿠폰 기준) 물고 있어야 한다 — 세션 단위 락이라
 * GET_LOCK/RELEASE_LOCK을 같은 Connection에서 호출해야 하기 때문이다.
 *
 * <p>이 커넥션을 메인 풀(spring.datasource.hikari.maximum-pool-size, JPA·배치 스텝이 전부
 * 공유)에서 빌리면, 관리자가 서로 다른 쿠폰 여러 개를 동시에 검증할 때(락끼리는 안 겹치므로
 * 정상적으로 병렬 허용됨) 그 커넥션들이 메인 풀을 오래 잠식해 실제 발급 트래픽이나 다른 배치
 * 스텝의 커넥션 확보를 굶길 수 있다. 락 전용으로 작고 독립된 풀을 따로 둬서 이 경합을 완전히
 * 분리한다 — 이 풀이 모자라면 정합성 검증 요청끼리만 서로 기다리고, 메인 풀은 영향받지 않는다.
 *
 * <p>다른 곳(BatchRepositoryConfig, BatchSchemaInitializerConfig, ReconciliationJobConfig 등)의
 * {@code DataSource dataSource} 주입은 그대로 둔다 — Spring Boot가 자동 설정하는 기본
 * DataSource 빈의 이름이 "dataSource"라, 한정자 없는 주입 지점은 이름 일치로 계속 그 빈을
 * 받는다. 이 빈은 반드시 {@code @Qualifier("reconciliationLockDataSource")}로만 주입한다.
 */
@Configuration
public class ReconciliationLockDataSourceConfig {

    @Bean
    public DataSource reconciliationLockDataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            @Value("${spring.datasource.driver-class-name}") String driverClassName
    ) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName(driverClassName);
        config.setPoolName("reconciliation-lock-pool");
        // 동시에 서로 다른 쿠폰을 검증하는 관리자 요청 수가 이 값을 넘으면 그때부터는
        // 커넥션을 못 구해 기다리거나 타임아웃난다 — 관리자 전용 저빈도 기능이라 넉넉하다.
        config.setMaximumPoolSize(3);
        return new HikariDataSource(config);
    }
}
