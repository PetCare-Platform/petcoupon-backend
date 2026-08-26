package com.mycom.petcoupon.global.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/*
 * 관리자 인증 설정(admin.auth.*)을 보관하는 Properties 클래스.
 *
 * 인증 코드는 팀이 공유하는 장기 비밀이고, 그걸로 발급받는 세션 토큰은 만료되는 단기
 * 자격증명이다. 이 둘을 나눠 둔 덕분에 브라우저에는 토큰만 돌아다니고, 유출되더라도
 * sessionTtl이 지나면 저절로 무효가 된다. 인증 코드가 새면 재배포가 필요하다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "admin.auth")
public class AdminAuthProperties {

	// TODO: 배포 전 제거. 개발 편의용 기본값이라 이대로 배포되면 누구나 이 코드로
	//       세션을 받아 /admin/** 전체를 호출할 수 있다. application.properties의
	//       기본값과 반드시 같이 지울 것.
	public static final String LOCAL_DEV_CODE = "local-dev-admin-auth-code";

	/**
	 * 세션을 발급받을 때 제시해야 하는 인증 코드.
	 *
	 * 비어 있으면 어떤 값도 통과시키지 않아 /admin/** 전체가 닫힌 상태가 된다
	 * (AdminSessionServiceImpl.matchesAuthCode 참고). 설정 누락이 인증 우회로
	 * 이어지지 않게 하려는 의도이므로, 이 동작을 "통과"로 바꾸면 안 된다.
	 */
	private String code;

	/**
	 * 발급된 세션 토큰의 유효 기간. application.properties에서 ISO-8601 Duration
	 * 형식(PT8H, PT30M 등)으로 지정하며, 환경변수 ADMIN_SESSION_TTL로 덮어쓸 수 있다.
	 *
	 * 여기 기본값은 프로퍼티 자체가 없을 때를 위한 것이라 properties의 기본값과 같은
	 * 8시간으로 맞춰 둔다. 한쪽만 바꾸면 설정 파일 유무에 따라 동작이 달라진다.
	 */
	private Duration sessionTtl = Duration.ofHours(8);

	public boolean isUsingLocalDevCode() {
		return LOCAL_DEV_CODE.equals(code);
	}
}
