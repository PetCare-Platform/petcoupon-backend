package com.mycom.petcoupon.coupon.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mycom.petcoupon.coupon.issue.config.CouponIssueRedisKeys;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.global.auth.interceptor.AdminSessionInterceptor;
import com.mycom.petcoupon.global.auth.service.AdminSessionService;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.entity.enums.UserRole;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import tools.jackson.databind.ObjectMapper;

/**
 * 관리자 쿠폰 생성 API가 DB의 coupon/coupon_stock을 만든 뒤
 * 발급용 Redis 재고 키까지 함께 초기화하는지 검증한다(이슈 #180).
 *
 * 실제 MySQL/Redis에 붙는다: docker compose up -d
 */
@SpringBootTest(properties = {
		"event.status.scheduler.enabled=false",
		"coupon.status.enabled=false"
})
@AutoConfigureMockMvc
class AdminCouponCreateIntegrationTest {

	private static final String ADMIN_TOKEN = "admin-coupon-create-it-token";
	private static final int TOTAL_QUANTITY = 100;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@PersistenceContext
	private EntityManager entityManager;

	// 세션 저장소(Redis) 대신 검증만 가로챈다 — 이 테스트의 관심사는 쿠폰 생성 → Redis 재고 초기화지
	// 토큰 발급 흐름이 아니다.
	@MockitoBean
	private AdminSessionService adminSessionService;

	private TransactionTemplate transactionTemplate;
	private Long eventId;

	@BeforeEach
	void setUp() {
		org.mockito.Mockito.when(adminSessionService.isValid(ADMIN_TOKEN)).thenReturn(true);

		transactionTemplate = new TransactionTemplate(transactionManager);
		transactionTemplate.executeWithoutResult(status -> {
			AppUser admin = AppUser.builder()
					.name("관리자")
					.email("admin-coupon-create-it@test.com")
					.phone("010-1000-0000")
					.role(UserRole.ROLE_ADMIN)
					.build();
			entityManager.persist(admin);

			Event event = Event.builder()
					.createdBy(admin)
					.name("쿠폰 생성 통합 테스트 이벤트")
					.description("admin coupon create integration test")
					.openAt(LocalDateTime.of(2026, 8, 1, 9, 0))
					.closeAt(LocalDateTime.of(2026, 8, 31, 23, 59))
					.build();
			entityManager.persist(event);
			entityManager.flush();

			eventId = event.getEventId();
		});
	}

	@AfterEach
	void tearDown() {
		transactionTemplate.executeWithoutResult(status -> {
			@SuppressWarnings("unchecked")
			List<Long> couponIds = entityManager
					.createNativeQuery("SELECT coupon_id FROM coupon WHERE event_id = :eventId")
					.setParameter("eventId", eventId)
					.getResultList()
					.stream()
					.map(id -> ((Number) id).longValue())
					.toList();

			couponIds.forEach(this::clearRedisKeys);

			if (!couponIds.isEmpty()) {
				entityManager.createNativeQuery("DELETE FROM coupon_stock WHERE coupon_id IN :couponIds")
						.setParameter("couponIds", couponIds)
						.executeUpdate();
				entityManager.createNativeQuery("DELETE FROM coupon WHERE coupon_id IN :couponIds")
						.setParameter("couponIds", couponIds)
						.executeUpdate();
			}

			entityManager.createNativeQuery("DELETE FROM event_status_history WHERE event_id = :eventId")
					.setParameter("eventId", eventId)
					.executeUpdate();
			entityManager.createNativeQuery("DELETE FROM event WHERE event_id = :eventId")
					.setParameter("eventId", eventId)
					.executeUpdate();
			entityManager.createNativeQuery("DELETE FROM app_user WHERE email = 'admin-coupon-create-it@test.com'")
					.executeUpdate();
		});
	}

	// 생성 API 한 번으로 DB 재고와 Redis 재고가 모두 준비돼야 한다 — 별도 초기화 호출 없이
	// GET /admin/coupons/{couponId}/status가 initialized=true와 총수량 그대로의 잔여를 돌려준다.
	@Test
	void 쿠폰을_생성하면_Redis_발급_재고까지_초기화된다() throws Exception {
		MvcResult created = mockMvc.perform(post("/admin/events/{eventId}/coupons", eventId)
						.header(AdminSessionInterceptor.HEADER, ADMIN_TOKEN)
						.contentType(MediaType.APPLICATION_JSON)
						.content(createRequestJson()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.isSuccess").value(true))
				.andExpect(jsonPath("$.result.totalQuantity").value(TOTAL_QUANTITY))
				.andReturn();

		long couponId = objectMapper.readTree(created.getResponse().getContentAsString())
				.path("result").path("couponId").asLong();
		assertThat(couponId).isPositive();

		// Redis 재고 키가 총수량으로 세워져 있어야 한다.
		assertThat(redisTemplate.opsForValue().get(CouponIssueRedisKeys.stock(couponId)))
				.isEqualTo(String.valueOf(TOTAL_QUANTITY));

		// 생성 직후 실시간 현황 조회 — 초기화 완료(initialized=true), 잔여=총수량, 발급=0.
		mockMvc.perform(get("/admin/coupons/{couponId}/status", couponId)
						.header(AdminSessionInterceptor.HEADER, ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result.initialized").value(true))
				.andExpect(jsonPath("$.result.totalQuantity").value(TOTAL_QUANTITY))
				.andExpect(jsonPath("$.result.remainingQuantity").value(TOTAL_QUANTITY))
				.andExpect(jsonPath("$.result.issuedQuantity").value(0));
	}

	private void clearRedisKeys(Long couponId) {
		redisTemplate.delete(List.of(
				CouponIssueRedisKeys.stock(couponId),
				CouponIssueRedisKeys.applicants(couponId),
				CouponIssueRedisKeys.sequence(couponId),
				CouponIssueRedisKeys.requestSequence(couponId)
		));
	}

	private String createRequestJson() {
		return """
				{
				  "name": "쿠폰 생성 통합 테스트 쿠폰",
				  "discountType": "RATE",
				  "discountValue": 20,
				  "minOrderAmount": 30000,
				  "maxDiscountAmount": 10000,
				  "issueStartAt": "2026-08-10T09:00:00",
				  "issueEndAt": "2026-08-20T23:59:00",
				  "validDays": 7,
				  "totalQuantity": %d
				}
				""".formatted(TOTAL_QUANTITY);
	}
}
