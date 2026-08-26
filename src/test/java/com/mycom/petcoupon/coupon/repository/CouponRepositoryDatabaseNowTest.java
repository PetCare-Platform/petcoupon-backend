package com.mycom.petcoupon.coupon.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

/**
 * findDatabaseNow()는 네이티브 쿼리라 서비스 단위 테스트(Mock)로는 한 줄도 실행되지 않는다.
 * 수정 가능 여부를 이 값으로 판정하므로, 실제 MySQL에서 조회와 LocalDateTime 매핑이
 * 되는지 확인해 둔다. CouponStatusSchedulerServiceImplTest와 같은 이유의 통합 테스트.
 *
 * 실행 전 MySQL이 떠 있어야 한다: docker compose up -d mysql
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CouponRepositoryDatabaseNowTest {

	@Autowired
	private CouponRepository couponRepository;

	@Test
	void findDatabaseNowReturnsCurrentTimeFromDatabase() {
		LocalDateTime databaseNow = couponRepository.findDatabaseNow();

		assertThat(databaseNow).isNotNull();

		// 애플리케이션 시계와 같아야 한다는 게 아니라, 둘이 크게 어긋나 있지 않은지만 본다.
		// 여기서 크게 벌어지면 DB 타임존 설정이 잘못된 것이라 수정 가능 판정도 틀어진다.
		Duration gap = Duration.between(databaseNow, LocalDateTime.now()).abs();
		assertThat(gap).isLessThan(Duration.ofMinutes(1));
	}

	// CURRENT_TIMESTAMP(6)로 조회하는 이유. 초 단위로 잘리면 같은 초 안에서 발급 시작 직전과
	// 직후를 구분하지 못한다.
	//
	// 한 번만 재서 nano != 0을 단언하면 마이크로초가 마침 000000일 때(약 100만분의 1) 깨진다.
	// 초 단위로 잘리는 경우엔 몇 번을 재도 항상 0이므로, 여러 번 중 한 번이라도 0이 아니면
	// 정밀도가 살아 있다고 판정한다.
	@Test
	void findDatabaseNowKeepsSubSecondPrecision() {
		boolean anyFractionalSecond = false;

		for (int attempt = 0; attempt < 5; attempt++) {
			if (couponRepository.findDatabaseNow().getNano() != 0) {
				anyFractionalSecond = true;
				break;
			}
		}

		assertThat(anyFractionalSecond)
				.as("CURRENT_TIMESTAMP(6)가 초 단위로 잘리고 있다면 매번 nano=0이 된다")
				.isTrue();
	}
}
