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
        "coupon.status.enabled=false"
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

        System.out.println("=== defaultDataSource pool name: " + defaultHikari.getPoolName()
                + " maxPoolSize: " + defaultHikari.getMaximumPoolSize());
        System.out.println("=== lockDataSource pool name: " + lockHikari.getPoolName()
                + " maxPoolSize: " + lockHikari.getMaximumPoolSize());

        assertThat(defaultHikari.getPoolName()).isNotEqualTo(lockHikari.getPoolName());
        assertThat(defaultHikari.getMaximumPoolSize()).isEqualTo(10);
        assertThat(lockHikari.getMaximumPoolSize()).isEqualTo(3);
    }
}
