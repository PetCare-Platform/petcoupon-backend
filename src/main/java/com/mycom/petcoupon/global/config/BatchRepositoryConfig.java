package com.mycom.petcoupon.global.config;

import javax.sql.DataSource;

import org.springframework.batch.core.configuration.support.DefaultBatchConfiguration;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JdbcJobRepositoryFactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * DefaultBatchConfiguration.jobRepository()는 그냥 ResourcelessJobRepository를 하드코딩해서
 * 반환한다(DataSource를 자동 감지해서 JDBC 기반으로 바꿔주는 로직이 없다) — DataSource가 있건
 * 없건 항상 메모리 전용이라, BatchSchemaInitializerConfig가 만들어둔 BATCH_JOB_INSTANCE 등
 * 테이블에는 아무것도 기록되지 않고, Job 재시작 시 이미 끝난 Step도 못 알아보고 다시 실행한다
 * (실제로 재시작 테스트에서 재현·확인함).
 *
 * DefaultBatchConfiguration을 상속해서 jobRepository()를 직접 JDBC 기반으로 오버라이드해야
 * 한다 — 이게 이 클래스가 하는 공식 확장 지점이다(클래스 JavaDoc에 명시된 사용법).
 */
@Configuration
public class BatchRepositoryConfig extends DefaultBatchConfiguration {

    private final DataSource dataSource;
    private final PlatformTransactionManager transactionManager;

    public BatchRepositoryConfig(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.dataSource = dataSource;
        this.transactionManager = transactionManager;
    }

    @Override
    @Bean
    public JobRepository jobRepository() {
        try {
            JdbcJobRepositoryFactoryBean factory = new JdbcJobRepositoryFactoryBean();
            factory.setDataSource(dataSource);
            factory.setTransactionManager(transactionManager);
            factory.afterPropertiesSet();
            return factory.getObject();
        } catch (Exception e) {
            throw new IllegalStateException("JDBC 기반 JobRepository 초기화에 실패했습니다.", e);
        }
    }

    @Override
    protected PlatformTransactionManager getTransactionManager() {
        return transactionManager;
    }
}
