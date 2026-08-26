package com.mycom.petcoupon.global.auth.service;

import com.mycom.petcoupon.global.auth.dto.res.AdminSessionCreateResponse;

/**
 * 관리자 세션의 발급 · 검증 · 폐기를 담당한다.
 *
 * 공유 인증 코드로 "관리자인지"만 확인하는 구조라 "어느 관리자인지"는 알 수 없다.
 * 감사 이력에 실제 관리자를 남기려면 로그인 기반 인증이 선행돼야 한다.
 */
public interface AdminSessionService {

	/**
	 * 인증 코드를 확인하고 세션 토큰을 발급한다.
	 *
	 * @param authCode 설정된 관리자 인증 코드
	 * @return 발급된 토큰과 만료 시각
	 * @throws com.mycom.petcoupon.global.common.exception.GeneralException
	 *         코드가 일치하지 않거나 인증 코드가 설정돼 있지 않으면 COMMON401-0.
	 *         두 경우를 구분하지 않는 건 설정 상태가 외부에 드러나지 않게 하기 위함이다.
	 */
	AdminSessionCreateResponse issue(String authCode);

	/**
	 * 토큰이 살아 있는 세션인지 확인한다. 인터셉터가 매 요청마다 호출한다.
	 *
	 * 없는 토큰 · null · 빈 문자열 · 만료된 토큰을 모두 false로 처리한다.
	 * 호출부가 실패 사유를 구분할 수 없어야 응답에서도 구분이 새어나가지 않는다.
	 */
	boolean isValid(String token);

	/**
	 * 세션을 즉시 무효화한다. 이미 없는 토큰이면 아무 일도 하지 않는다.
	 *
	 * 토큰이 유출됐을 때 만료를 기다리지 않고 끊을 수 있는 유일한 수단이다.
	 */
	void revoke(String token);
}
