package com.mycom.petcoupon.idempotency.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.idempotency.entity.enums.IdempotencyStatus;
import com.mycom.petcoupon.user.entity.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * API 레벨(HTTP 요청) 멱등성 원장 — "같은 Idempotency-Key로 온 요청은 한 번만 반영한다"를 지키는 테이블.
 * (user_id, idempotency_key) 조합으로 유니크해서, 같은 유저가 같은 키를 재전송하면 항상 이 한 행으로 귀결된다.
 *
 * 상태는 IN_PROGRESS → SUCCEEDED / FAILED 로만 전이한다(IdempotencyKeyService 참고):
 *  - INSERT 시점엔 무조건 IN_PROGRESS (본처리 시작 전)
 *  - 본처리가 끝나면(성공이든 실패든) 결과를 response_status/response_body에 그대로 저장하고 SUCCEEDED/FAILED로 바꾼다
 *  - 다음에 같은 키가 다시 오면, 재실행하지 않고 저장된 응답을 그대로 돌려준다(REPLAY)
 */
@Entity
@Getter
@Table(
	name = "idempotency_key",
	uniqueConstraints = {
		@UniqueConstraint(
			name = "uk_idem_user_key",
			columnNames = {"user_id","idempotency_key"}
		)
	},
	indexes = {
		// 정리 배치(IdempotencyKeyServiceImpl.cleanupExpiredRecords)가 created_at 기준으로 스캔한다
		@Index(name = "idx_idem_created_at", columnList = "created_at")
	}
)
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyKey {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "idempotency_id")
	private Long idempotencyId;

	// 클라이언트가 요청 헤더(Idempotency-Key)로 보낸 값. 값 자체는 클라이언트가 정하고, 서버는 검증만 한다.
	@Column(name = "idempotency_key", nullable = false, length = 64)
	private String idempotencyKey;

	// 이 키를 누가 보냈는지 — (user, idempotency_key) 조합이 유니크 스코프
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id")
	private AppUser user;

	// 이 키가 어떤 쿠폰 신청에 쓰였는지 — request_hash 계산에도 쓰여서, 같은 키를 다른 쿠폰에 재사용하면 걸러낸다
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "coupon_id")
	private Coupon coupon;

	// 발급이 실제로 성사된 뒤(추후 단계) 연결할 필드. 지금 단계(Redis mock)는 CouponIssue를 안 만들어서 항상 null이다.
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "coupon_issue_id")
	private CouponIssue couponIssue;

	// 요청 내용(coupon+user)의 SHA-256 해시. 같은 idempotency_key인데 이 값이 다르면
	// "같은 키를 다른 요청에 재사용"한 것으로 보고 KEY_REUSED로 막는다 (matchesRequest 참고).
	@Column(name = "request_hash", nullable = false, length = 64)
	private String requestHash;

	// 아래 두 컬럼(response_status/response_body)은 SUCCEEDED·FAILED가 된 뒤에만 값이 채워진다.
	// REPLAY할 때 이 값을 그대로 HTTP 응답으로 돌려준다.
	@Column(name = "response_status")
	private Integer responseStatus;

	@Column(name = "response_body", columnDefinition = "json")
	private String responseBody;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private IdempotencyStatus status = IdempotencyStatus.IN_PROGRESS;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	// IN_PROGRESS 상태가 이 시각을 넘기면 "죽은 시도"로 간주하고 재처리를 허용한다(reclaim 참고).
	// 본처리 도중 서버가 죽어서 영원히 IN_PROGRESS로 남는 것을 막기 위한 안전장치.
	@Column(name = "expires_at", nullable = false)
	private LocalDateTime expiresAt;

	// 새 시도 생성 전용 생성자. 상태는 항상 IN_PROGRESS로 시작하고, 응답 관련 필드는 아직 없어서 안 받는다.
	@Builder
	public IdempotencyKey(AppUser user, Coupon coupon, String idempotencyKey, String requestHash, LocalDateTime expiresAt) {
		this.user = user;
		this.coupon = coupon;
		this.idempotencyKey = idempotencyKey;
		this.requestHash = requestHash;
		this.status = IdempotencyStatus.IN_PROGRESS;
		this.expiresAt = expiresAt;
	}

	public boolean isExpired(LocalDateTime now) {
		return this.expiresAt.isBefore(now);
	}

	// 지금 들어온 요청이 이 레코드를 처음 만들었던 요청과 같은 내용인지 확인한다.
	public boolean matchesRequest(String requestHash) {
		return this.requestHash.equals(requestHash);
	}

	// 죽은 IN_PROGRESS 시도를 재사용 — 새 만료시각으로 리셋하고 이전 결과 흔적을 지운다.
	// (같은 idempotency_key 행을 새로 INSERT하지 않고 그대로 이어받아 본처리를 다시 시작하는 용도)
	public void reclaim(LocalDateTime newExpiresAt) {
		this.status = IdempotencyStatus.IN_PROGRESS;
		this.expiresAt = newExpiresAt;
		this.responseStatus = null;
		this.responseBody = null;
	}

	// 본처리 완료(성공/실패 모두 호출됨) — 응답을 그대로 저장해서 재요청 시 재사용한다.
	public void complete(IdempotencyStatus status, Integer responseStatus, String responseBody) {
		this.status = status;
		this.responseStatus = responseStatus;
		this.responseBody = responseBody;
	}
}
