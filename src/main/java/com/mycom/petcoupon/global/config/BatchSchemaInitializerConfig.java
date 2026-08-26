package com.mycom.petcoupon.global.config;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * Spring Boot 4.1부터 spring.batch.jdbc.initialize-schema가 없어져서(BatchProperties에 job.name만 남음),
 * Batch 메타데이터 테이블(BATCH_JOB_INSTANCE 등)을 직접 초기화한다.
 *
 * spring-batch-core가 내장한 schema-mysql.sql은 CREATE TABLE IF NOT EXISTS가 아니라 순수
 * CREATE TABLE이라, 매 기동마다 무조건 실행하면 두 번째 기동부터 "테이블 이미 존재" 에러가 난다.
 * 그래서 BATCH_JOB_INSTANCE 존재 여부를 먼저 확인하고, 없을 때만 스크립트를 실행한다.
 */
@Configuration
public class BatchSchemaInitializerConfig {

    private static final String MARKER_TABLE = "BATCH_JOB_INSTANCE";
    private static final String SCHEMA_SCRIPT = "org/springframework/batch/core/schema-mysql.sql";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public ApplicationRunner batchSchemaInitializerRunner(DataSource dataSource) {
        return (ApplicationArguments args) -> {
            if (tableExists(dataSource, MARKER_TABLE)) {
                return;
            }
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new ClassPathResource(SCHEMA_SCRIPT));
            DatabasePopulatorUtils.execute(populator, dataSource);
        };
    }

    private boolean tableExists(DataSource dataSource, String tableName) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet resultSet = metaData.getTables(null, null, tableName, null)) {
                return resultSet.next();
            }
        }
    }
}
