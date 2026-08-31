package com.mycom.petcoupon.global.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.mycom.petcoupon.global.auth.AdminAuthProperties;
import com.mycom.petcoupon.global.auth.converter.AdminAuthConverter;
import com.mycom.petcoupon.global.auth.dto.res.AdminSessionCreateResponse;
import com.mycom.petcoupon.global.common.code.CommonErrorCode;
import com.mycom.petcoupon.global.common.exception.GeneralException;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 인증 코드를 확인해 단기 세션 토큰을 발급하고, 그 토큰을 검증·폐기한다.
 *
 * 인증 코드는 팀이 공유하는 장기 비밀이라 브라우저나 로그에 돌아다니면 안 된다.
 * 그래서 코드는 세션을 받을 때 한 번만 쓰고 이후 요청은 만료되는 토큰으로 처리한다.
 * 인증 코드가 유출되면 재배포가 필요하지만, 세션은 revoke 한 번으로 끊을 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSessionServiceImpl implements AdminSessionService {

	private static final String SESSION_KEY_PREFIX = "admin:session:";

	// 토큰 엔트로피. 32바이트면 무차별 대입이 현실적으로 불가능하다.
	private static final int TOKEN_BYTE_LENGTH = 32;

	private final StringRedisTemplate redisTemplate;
	private final AdminAuthProperties properties;
	private final AdminAuthConverter adminAuthConverter;

	private final SecureRandom secureRandom = new SecureRandom();

	// 인증 코드 설정 상태를 기동 시 한 번만 알린다.
	// 요청마다 찍으면 설정이 빠진 채 배포됐을 때 로그가 요청량만큼 쌓여 마비된다.
	@PostConstruct
	void logAuthCodeConfiguration() {
		if (!hasAuthCode()) {
			log.error("ADMIN_AUTH_CODE가 설정되지 않았습니다."
					+ " 세션을 발급할 수 없어 /admin/**은 차단 상태입니다.");
			return;
		}

		if (properties.isUsingLocalDevCode()) {
			log.warn("관리자 인증 코드가 개발용 기본값입니다."
					+ " 배포 환경이라면 ADMIN_AUTH_CODE를 반드시 설정하세요.");
		}
	}

	@Override
	public AdminSessionCreateResponse issue(String authCode) {
		if (!matchesAuthCode(authCode)) {
			// 실패가 기록되지 않으면 무차별 대입을 탐지할 방법이 없다.
			// 단, 시도된 코드 자체는 남기지 않는다 — 로그가 곧 인증 정보가 된다.
			log.warn("관리자 세션 발급 실패: 인증 코드가 일치하지 않습니다.");

			throw new GeneralException(CommonErrorCode.UNAUTHORIZED);
		}

		String token = generateToken();
		Duration ttl = properties.getSessionTtl();

		LocalDateTime issuedAt = LocalDateTime.now();

		// 토큰을 평문으로 저장하면 Redis가 읽히는 순간 그대로 관리자 권한이 된다.
		// 해시를 키로 두면 저장소에서 원본 토큰을 복원할 수 없고, 조회가 해시 키 매칭이라
		// 토큰 길이나 접두사가 응답 시간에 드러나지도 않는다.
		redisTemplate.opsForValue().set(toSessionKey(token), issuedAt.toString(), ttl);

		LocalDateTime expiresAt = issuedAt.plus(ttl);

		// 토큰은 남기지 않는다. 로그에 찍히는 순간 그걸 본 사람이 관리자가 된다.
		log.debug("관리자 세션을 발급했습니다. expiresAt={}", expiresAt);

		return adminAuthConverter.toCreateResponse(token, expiresAt);
	}

	@Override
	public boolean isValid(String token) {
		if (token == null || token.isBlank()) {
			return false;
		}

		return Boolean.TRUE.equals(redisTemplate.hasKey(toSessionKey(token)));
	}

	@Override
	public void revoke(String token) {
		if (token == null || token.isBlank()) {
			return;
		}

		Boolean deleted = redisTemplate.delete(toSessionKey(token));

		// 유출된 토큰을 끊었는지 사후에 확인할 수 있어야 한다.
		log.debug("관리자 세션 폐기 요청을 처리했습니다. 삭제됨={}", Boolean.TRUE.equals(deleted));
	}

	// 설정이 비어 있으면 어떤 코드도 통과시키지 않는다. ADMIN_AUTH_CODE를 빠뜨린 채 뜬
	// 서버가 관리자 API를 열어두는 것보다, 아무도 세션을 못 받아
	// 즉시 알아채는 쪽이 안전하다.
	private boolean matchesAuthCode(String provided) {
		// 설정 누락은 기동 시 ERROR로 이미 알렸다. 여기서 또 찍으면 요청마다 쌓인다.
		if (!hasAuthCode()) {
			log.debug("인증 코드가 설정되지 않아 세션 발급을 거부합니다.");

			return false;
		}

		if (provided == null) {
			return false;
		}

		String expected = properties.getCode();

		// String.equals는 첫 불일치에서 즉시 반환해서,
		// 맞는 접두사가 길수록 응답이 미세하게 느려진다.
		return MessageDigest.isEqual(
				provided.getBytes(StandardCharsets.UTF_8),
				expected.getBytes(StandardCharsets.UTF_8)
		);
	}

	private boolean hasAuthCode() {
		String code = properties.getCode();

		return code != null && !code.isBlank();
	}

	private String generateToken() {
		byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
		secureRandom.nextBytes(bytes);

		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private String toSessionKey(String token) {
		return SESSION_KEY_PREFIX + hashToHex(token);
	}

	private String hashToHex(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));

			return HexFormat.of().formatHex(hashed);

		} catch (NoSuchAlgorithmException e) {
			// SHA-256은 모든 JVM이 제공하도록 명세에 규정돼 있어 실제로는 발생하지 않는다.
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
		}
	}
}
