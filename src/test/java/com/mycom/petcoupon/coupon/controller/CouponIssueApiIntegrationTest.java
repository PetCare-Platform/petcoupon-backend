package com.mycom.petcoupon.coupon.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.idempotency.entity.enums.IdempotencyStatus;
import com.mycom.petcoupon.idempotency.repository.IdempotencyKeyRepository;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.entity.enums.UserRole;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 담당(이성집) 시나리오 TC-07 ~ TC-10, TC-30, TC-33, TC-38, TC-43의 통합 테스트.
 * CouponIssueConcurrencyIntegrationTest와 동일하게 @SpringBootTest로 실제 MySQL에 붙는다.
 * 실행 전 MySQL/Redis/Kafka가 떠 있어야 한다: docker compose up -d
 *
 * #67 머지 이후 신청 API(CouponController → CouponIssueServiceImpl)는 항상 즉시 200/WAITING만
 * 반환하고, 실 재고 판정(Lua)과 최종 확정은 Redis Stream Consumer → Outbox → Kafka Consumer가
 * 비동기로 수행한다 — TC-43처럼 "재고가 실제로 어떻게 됐는지" 검증하는 테스트는 즉시 응답이 아니라
 * GET .../coupon-issue-requests/status 폴링(awaitRequestResolved 참고)으로 최종 결과를 기다린다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CouponIssueApiIntegrationTest {

	private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
	private static final long TIMEOUT_MILLIS = 15_000L;
	private static final long POLL_INTERVAL_MILLIS = 100L;

	@Autowired
	private MockMvc mockMvc;

	@PersistenceContext
	private EntityManager entityManager;

	@Autowired
	private IdempotencyKeyRepository idempotencyKeyRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private TransactionTemplate transactionTemplate;

	private AppUser admin;
	private Event event;
	private Coupon coupon;
	private Coupon secondCoupon;
	private AppUser requester;

	@BeforeEach
	void setUp() {
		transactionTemplate = new TransactionTemplate(transactionManager);
		transactionTemplate.executeWithoutResult(status -> setUpData());
	}

	private void setUpData() {
		admin = AppUser.builder()
				.name("관리자")
				.email("issue-api-admin@test.com")
				.phone("010-1111-0000")
				.role(UserRole.ROLE_ADMIN)
				.build();
		entityManager.persist(admin);

		event = Event.builder()
				.createdBy(admin)
				.name("발급 API 통합 테스트 이벤트")
				.description("coupon issue api integration test")
				.openAt(LocalDateTime.of(2026, 8, 1, 9, 0))
				.closeAt(LocalDateTime.of(2026, 8, 31, 23, 59))
				.build();
		entityManager.persist(event);

		coupon = Coupon.builder()
				.event(event)
				.name("발급 API 통합 테스트 쿠폰")
				.discountType(DiscountType.FIXED_AMOUNT)
				.discountValue(1_000)
				.minOrderAmount(5_000)
				.issueStartAt(LocalDateTime.of(2026, 8, 1, 9, 0))
				.issueEndAt(LocalDateTime.of(2026, 8, 31, 23, 59))
				.validDays(7)
				.build();
		entityManager.persist(coupon);

		CouponStock stock = CouponStock.builder()
				.coupon(coupon)
				.totalQuantity(10)
				.build();
		entityManager.persist(stock);

		// coupon_issue.uk_issue_coupon_user(coupon_id, user_id) 유니크 제약 때문에 같은 유저가 같은 쿠폰을
		// 두 번 발급받을 수 없다 — TC-10(목록 정렬)처럼 한 유저의 발급 건이 2개 이상 필요한 테스트를 위해
		// 쿠폰을 하나 더 둔다.
		secondCoupon = Coupon.builder()
				.event(event)
				.name("발급 API 통합 테스트 쿠폰 2")
				.discountType(DiscountType.FIXED_AMOUNT)
				.discountValue(1_000)
				.minOrderAmount(5_000)
				.issueStartAt(LocalDateTime.of(2026, 8, 1, 9, 0))
				.issueEndAt(LocalDateTime.of(2026, 8, 31, 23, 59))
				.validDays(7)
				.build();
		entityManager.persist(secondCoupon);

		CouponStock secondStock = CouponStock.builder()
				.coupon(secondCoupon)
				.totalQuantity(10)
				.build();
		entityManager.persist(secondStock);

		requester = AppUser.builder()
				.name("발급신청유저")
				.email("issue-api-requester@test.com")
				.phone("010-2222-0000")
				.role(UserRole.ROLE_MEMBER)
				.build();
		entityManager.persist(requester);

		entityManager.flush();
	}

	@AfterEach
	void tearDown() {
		transactionTemplate.executeWithoutResult(status -> tearDownData());
	}

	private void tearDownData() {
		List<Long> couponIds = List.of(coupon.getCouponId(), secondCoupon.getCouponId());

		couponIds.forEach(this::clearRedisStock);

		entityManager.createNativeQuery("DELETE FROM idempotency_key WHERE coupon_id IN :couponIds")
				.setParameter("couponIds", couponIds)
				.executeUpdate();
		// TC-43만 실제 Lua/Outbox/Kafka 파이프라인을 태우므로 다른 TC에서는 항상 0건 삭제 — issue_message가
		// coupon을 FK로 물고 있어서, 아래 coupon 삭제보다 먼저 지워야 FK 위반이 안 난다.
		entityManager.createNativeQuery("DELETE FROM issue_message WHERE coupon_id IN :couponIds")
				.setParameter("couponIds", couponIds)
				.executeUpdate();
		// #119(쿠폰 발급 알림 로그)에서 coupon_issue를 FK로 무는 notification_log가 추가돼서,
		// 위와 같은 이유로 coupon_issue 삭제보다 먼저 지워야 한다.
		entityManager.createNativeQuery(
				"DELETE n FROM notification_log n JOIN coupon_issue ci ON n.coupon_issue_id = ci.coupon_issue_id WHERE ci.coupon_id IN :couponIds")
				.setParameter("couponIds", couponIds)
				.executeUpdate();
		entityManager.createNativeQuery(
				"DELETE h FROM coupon_issue_history h JOIN coupon_issue ci ON h.coupon_issue_id = ci.coupon_issue_id WHERE ci.coupon_id IN :couponIds")
				.setParameter("couponIds", couponIds)
				.executeUpdate();
		entityManager.createNativeQuery("DELETE FROM coupon_issue WHERE coupon_id IN :couponIds")
				.setParameter("couponIds", couponIds)
				.executeUpdate();
		entityManager.createNativeQuery("DELETE FROM coupon_stock WHERE coupon_id IN :couponIds")
				.setParameter("couponIds", couponIds)
				.executeUpdate();
		entityManager.createNativeQuery("DELETE FROM coupon WHERE coupon_id IN :couponIds")
				.setParameter("couponIds", couponIds)
				.executeUpdate();
		entityManager.createNativeQuery("DELETE FROM event WHERE event_id = :eventId")
				.setParameter("eventId", event.getEventId())
				.executeUpdate();
		entityManager.createNativeQuery("DELETE FROM app_user WHERE email LIKE 'issue-api-%@test.com'")
				.executeUpdate();
	}

	// TC-07: 쿠폰 발급 신청 (접수)
	// 참고: 시나리오 문서는 202 + result=WAITING을 기대하지만, 현재 CouponIssueServiceImpl은
	// 실 Redis Lua/Kafka가 아니라 MockRedisCouponStockService(인메모리)를 그대로 타고 있어
	// 200과 {couponId, userId}만 반환한다. 실 파이프라인이 연결되면 기대값을 다시 맞춰야 한다.
	@Test
	void 쿠폰_발급을_신청하면_성공_응답을_받는다() throws Exception {
		mockMvc.perform(post("/coupons/{couponId}/issues", coupon.getCouponId())
						.header(IDEMPOTENCY_HEADER, "tc07-" + UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":" + requester.getUserId() + "}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.isSuccess").value(true))
				.andExpect(jsonPath("$.result.couponId").value(coupon.getCouponId()))
				.andExpect(jsonPath("$.result.userId").value(requester.getUserId()));
	}

	// TC-08: 비동기 확정 후 결과 조회
	// TC-43과 마찬가지로 실제 재고 판정이 필요해서 Redis 재고를 초기화한다. GET .../status(idempotencyKey)로
	// 폴링해서 couponIssueId가 채워질 때까지 기다린 뒤, 시나리오 문서가 지정한 GET /coupon-issues/{id}/status로
	// 실제로 ISSUED·사용가능 상태가 됐는지 확인한다.
	@Test
	void 접수_후_폴링하면_일정_시간_내에_ISSUED_상태가_된다() throws Exception {
		initRedisStock(coupon.getCouponId(), 10);

		String key = "tc08-" + UUID.randomUUID();

		mockMvc.perform(post("/coupons/{couponId}/issues", coupon.getCouponId())
						.header(IDEMPOTENCY_HEADER, key)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":" + requester.getUserId() + "}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.result.status").value("WAITING"));

		JsonNode resolved = awaitRequestResolved(requester.getUserId(), key);
		long couponIssueId = resolved.path("result").path("couponIssueId").asLong(0);
		assertThat(couponIssueId).isPositive();
		// 재고 10개짜리 쿠폰에 첫 신청이니 순번은 1이어야 한다.
		assertThat(resolved.path("result").path("sequenceNo").asLong(0)).isEqualTo(1L);

		mockMvc.perform(get("/coupon-issues/{couponIssueId}/status", couponIssueId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result.status").value("ISSUED"))
				.andExpect(jsonPath("$.result.isUsable").value(true));
	}

	// TC-09: 발급 상세 조회
	@Test
	void 발급_상세를_조회하면_쿠폰코드와_만료일을_포함해_사용가능_여부를_돌려준다() throws Exception {
		CouponIssue issue = persistCouponIssue(requester, IssueStatus.ISSUED, "TC09-CODE");

		mockMvc.perform(get("/coupon-issues/{couponIssueId}", issue.getCouponIssueId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.isSuccess").value(true))
				.andExpect(jsonPath("$.result.couponIssueId").value(issue.getCouponIssueId()))
				.andExpect(jsonPath("$.result.couponCode").value("TC09-CODE"))
				.andExpect(jsonPath("$.result.status").value("ISSUED"))
				.andExpect(jsonPath("$.result.isUsable").value(true));
	}

	// TC-10: 내 발급 내역 목록
	// coupon_issue.uk_issue_coupon_user(coupon_id, user_id) 유니크 제약 때문에 같은 유저가 같은 쿠폰을
	// 두 번 발급받을 수 없다 — 목록에 2건을 채우기 위해 서로 다른 쿠폰으로 발급한다.
	@Test
	void 내_발급_내역_목록은_최신순으로_정렬된다() throws Exception {
		CouponIssue older = persistCouponIssue(coupon, requester, IssueStatus.ISSUED, "TC10-OLD");
		Thread.sleep(10); // createdAt이 서로 달라지도록(@CreatedDate는 persist 시점 자동 부여) 최소 간격을 둔다
		CouponIssue newer = persistCouponIssue(secondCoupon, requester, IssueStatus.ISSUED, "TC10-NEW");

		mockMvc.perform(get("/users/{userId}/coupon-issue-requests", requester.getUserId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result.length()").value(2))
				.andExpect(jsonPath("$.result[0].couponIssueId").value(newer.getCouponIssueId()))
				.andExpect(jsonPath("$.result[1].couponIssueId").value(older.getCouponIssueId()));
	}

	// TC-30: 존재하지 않는 쿠폰에 신청
	@Test
	void 존재하지_않는_쿠폰에_신청하면_404를_반환한다() throws Exception {
		long nonExistentCouponId = 999_999_999L;

		mockMvc.perform(post("/coupons/{couponId}/issues", nonExistentCouponId)
						.header(IDEMPOTENCY_HEADER, "tc30-" + UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":" + requester.getUserId() + "}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("COUPON404-0"));
	}

	// TC-33: 존재하지 않는 발급 건 조회
	@Test
	void 존재하지_않는_발급_건을_조회하면_404를_반환한다() throws Exception {
		long nonExistentIssueId = 999_999_999L;

		mockMvc.perform(get("/coupon-issues/{couponIssueId}", nonExistentIssueId))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("COUPON404-1"));
	}

	// TC-38: 존재하지 않는 사용자의 발급 내역 조회
	@Test
	void 존재하지_않는_사용자의_발급_내역을_조회하면_404를_반환한다() throws Exception {
		long nonExistentUserId = 999_999_999L;

		mockMvc.perform(get("/users/{userId}/coupon-issue-requests", nonExistentUserId))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("USER404-0"));
	}

	// TC-43: 동일 requestId(Idempotency-Key) 재전송 + 재고 소진
	// "최초 순번 반환"은 검증하지 못한다 — CouponIssueCreateResponse에 sequenceNo 필드가 아예 없다(별도 확인 필요).
	// #67 이후 신청 API는 항상 즉시 WAITING을 반환하므로 "즉시 409"는 더 이상 기대할 수 없다 — 대신
	// GET .../status를 폴링해서 실제 재고 소진분이 비동기로 SOLD_OUT 확정되는지 확인한다.
	@Test
	void 동일_Idempotency_Key로_재전송하면_최초_응답을_그대로_반환하고_재고_소진분은_비동기로_실패_확정된다() throws Exception {
		// 다른 TC들은 Redis 재고 키를 일부러 초기화하지 않는다 — 초기화하면 Stream Consumer가 실제로
		// Lua/Outbox/Kafka까지 다 태워서 coupon_issue/issue_message 행을 만들어버리고, 그 정리까지
		// 책임져야 해서 다른 테스트까지 불안정해진다. 실제 재고 판정이 필요한 TC-43에서만 초기화한다.
		initRedisStock(coupon.getCouponId(), 10);

		String key = "tc43-" + UUID.randomUUID();
		String body = "{\"userId\":" + requester.getUserId() + "}";

		MvcResult first = mockMvc.perform(post("/coupons/{couponId}/issues", coupon.getCouponId())
						.header(IDEMPOTENCY_HEADER, key)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.result.status").value("WAITING"))
				.andReturn();

		MvcResult retry = mockMvc.perform(post("/coupons/{couponId}/issues", coupon.getCouponId())
						.header(IDEMPOTENCY_HEADER, key)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isAccepted())
				.andReturn();

		// 문자열 그대로 비교하지 않는다 — Jackson이 필드 순서를 항상 보장하지 않는다.
		// (덤으로 확인된 별개 이슈: CustomResponse가 "isSuccess"와 "success"를 중복 직렬화한다.
		//  Lombok이 만드는 isSuccess() 게터가 필드의 @JsonProperty("isSuccess")와 별개로
		//  "success"라는 프로퍼티로도 다시 잡히는 것으로 보인다 — 응답 재현 비교에는 영향 없지만
		//  실제 응답 바디에 불필요한 필드가 하나 더 나가고 있어 CustomResponse 쪽 확인이 필요하다.)
		assertThat(objectMapper.readTree(retry.getResponse().getContentAsString()))
				.isEqualTo(objectMapper.readTree(first.getResponse().getContentAsString()));

		// 재전송이 본처리(CouponIssueServiceImpl.issue, Stream 재발행)를 다시 태우지 않았는지 —
		// 이 시점엔 컨트롤러가 접수 즉시 SUCCEEDED(WAITING)로 저장해둔 상태라, 아직 "재고가 실제로 확정됐는지"는 안 알려준다.
		assertThat(idempotencyKeyRepository.findByUser_UserIdAndIdempotencyKey(requester.getUserId(), key))
				.isPresent()
				.get()
				.satisfies(record -> assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.SUCCEEDED));

		// 최초 신청이 비동기로 실제 발급까지 확정되는지 폴링 확인 — couponIssueId가 채워지면 진짜로 끝난 것.
		JsonNode firstResolved = awaitRequestResolved(requester.getUserId(), key);
		assertThat(firstResolved.path("result").path("couponIssueId").asLong(0)).isPositive();
		// 최초 순번 반환 검증 — 재전송이 별도로 재고를 선점하지 않았으므로 이 쿠폰의 첫 신청인 순번 1이어야 한다.
		assertThat(firstResolved.path("result").path("sequenceNo").asLong(0)).isEqualTo(1L);

		// coupon_stock.total_quantity(10)만큼만 성공하도록, 나머지 자리를 다른 유저들로 채운다(최초 신청자 1명 + 9명).
		for (int i = 0; i < 9; i++) {
			AppUser other = persistExtraUser("issue-api-tc43-" + i);
			String otherKey = "tc43-extra-" + i + "-" + UUID.randomUUID();

			mockMvc.perform(post("/coupons/{couponId}/issues", coupon.getCouponId())
							.header(IDEMPOTENCY_HEADER, otherKey)
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"userId\":" + other.getUserId() + "}"))
					.andExpect(status().isAccepted());

			JsonNode resolved = awaitRequestResolved(other.getUserId(), otherKey);
			assertThat(resolved.path("result").path("couponIssueId").asLong(0)).isPositive();
		}

		// 재고 10개가 이미 다 소진됐으니, 11번째 신청자는 즉시는 WAITING이지만 비동기로 SOLD_OUT 확정돼야 한다.
		AppUser oneTooMany = persistExtraUser("issue-api-tc43-sold-out");
		String soldOutKey = "tc43-sold-out-" + UUID.randomUUID();

		mockMvc.perform(post("/coupons/{couponId}/issues", coupon.getCouponId())
						.header(IDEMPOTENCY_HEADER, soldOutKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":" + oneTooMany.getUserId() + "}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.result.status").value("WAITING"));

		JsonNode soldOutResolved = awaitRequestResolved(oneTooMany.getUserId(), soldOutKey);
		assertThat(soldOutResolved.path("code").asText()).isEqualTo("COUPON409-0");
	}

	// GET .../coupon-issue-requests/status를 폴링해서, 접수 시점에 저장된 WAITING 응답이 아니라
	// Consumer/Persister가 비동기로 확정한 최종 결과(couponIssueId가 채워진 성공, 또는 에러 코드가 담긴 실패)로
	// 바뀔 때까지 기다린 뒤 그 응답 바디를 돌려준다. CouponIssuePipelineIntegrationTest의 awaitUntil과 같은 패턴.
	private JsonNode awaitRequestResolved(Long userId, String idempotencyKey) throws Exception {
		long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
		JsonNode lastBody = null;

		while (System.currentTimeMillis() < deadline) {
			MvcResult result = mockMvc.perform(get("/users/{userId}/coupon-issue-requests/status", userId)
							.queryParam("idempotencyKey", idempotencyKey))
					.andReturn();

			lastBody = objectMapper.readTree(result.getResponse().getContentAsString());

			// 접수 직후 저장된 응답의 result.status는 항상 "WAITING" — Consumer/Persister가 최종 결과로
			// 덮어쓰기 전까지는 이 값 그대로다. 최종 성공 응답(toCreateResponse(CouponIssue))은 status를
			// 아예 채우지 않고, 최종 실패 응답은 result 자체가 없으므로 두 경우 다 자연히 "WAITING"이 아니게 된다.
			if (!"WAITING".equals(lastBody.path("result").path("status").asText(null))) {
				return lastBody;
			}

			Thread.sleep(POLL_INTERVAL_MILLIS);
		}

		fail("idempotencyKey=" + idempotencyKey + " 요청이 제한 시간 내에 확정되지 않았습니다. 마지막 응답: " + lastBody);
		return null; // 위 fail()이 항상 예외를 던지므로 도달하지 않음 — 컴파일러 만족용
	}

	private void initRedisStock(Long couponId, int quantity) {
		clearRedisStock(couponId);
		redisTemplate.opsForValue().set(stockKey(couponId), String.valueOf(quantity));
	}

	private void clearRedisStock(Long couponId) {
		redisTemplate.delete(List.of(stockKey(couponId), applicantsKey(couponId), sequenceKey(couponId), requestSequenceKey(couponId)));
	}

	private String stockKey(Long couponId) {
		return "coupon:issue:stock:{" + couponId + "}";
	}

	private String applicantsKey(Long couponId) {
		return "coupon:issue:applicants:{" + couponId + "}";
	}

	private String sequenceKey(Long couponId) {
		return "coupon:issue:sequence:{" + couponId + "}";
	}

	private String requestSequenceKey(Long couponId) {
		return "coupon:issue:request-sequence:{" + couponId + "}";
	}

	private AppUser persistExtraUser(String emailPrefix) {
		return transactionTemplate.execute(txStatus -> {
			AppUser user = AppUser.builder()
					.name("추가유저-" + emailPrefix)
					.email(emailPrefix + "@test.com")
					.phone("010-3333-0000")
					.role(UserRole.ROLE_MEMBER)
					.build();
			entityManager.persist(user);
			entityManager.flush();
			return user;
		});
	}

	private CouponIssue persistCouponIssue(AppUser user, IssueStatus status, String couponCode) {
		return persistCouponIssue(coupon, user, status, couponCode);
	}

	private CouponIssue persistCouponIssue(Coupon targetCoupon, AppUser user, IssueStatus status, String couponCode) {
		return transactionTemplate.execute(txStatus -> {
			CouponIssue issue = CouponIssue.builder()
					.coupon(targetCoupon)
					.user(user)
					.sequenceNo(1)
					.couponCode(couponCode)
					.requestId("issue-api-request-" + couponCode)
					.status(status)
					.expiresAt(LocalDateTime.now().plusDays(1))
					.build();
			entityManager.persist(issue);
			entityManager.flush();
			return issue;
		});
	}
}
