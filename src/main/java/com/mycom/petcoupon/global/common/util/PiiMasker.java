package com.mycom.petcoupon.global.common.util;

/**
 * 개인정보 마스킹.
 *
 * <p>과제 전제 조건상 개인정보는 로그와 응답 데이터에 평문으로 남으면 안 된다.
 * 저장·로깅 직전에 이 클래스를 거치게 해서 원본이 밖으로 나가지 않도록 한다.
 *
 * <p><b>전부 가리지 않고 앞뒤 일부를 남긴다.</b> 장애가 났을 때 어느 사용자의 건인지
 * 구분할 수 있어야 로그가 쓸모가 있기 때문이다. 전체를 별표로 바꾸면 "누군가에게 실패했다"는
 * 사실만 남고 추적이 불가능해진다.
 *
 * <p><b>{@code null} 을 {@code null} 로 돌려주지 않는다.</b> 호출부가 그대로 저장하면
 * {@code notification_log.recipient_masked} 같은 NOT NULL 컬럼에서 터진다. 마스킹은
 * 부가 기능이라 본처리를 실패시키면 안 되므로, 값이 없다는 사실을 문자열로 표현해 넘긴다.
 */
public final class PiiMasker {

	// 값이 없거나 형식을 알아볼 수 없을 때 대신 넣는 값.
	// 컬럼이 NOT NULL 이라 빈 문자열로 두면 "마스킹된 것"과 "값이 없던 것"을 구분할 수 없다.
	public static final String UNKNOWN = "(없음)";

	private static final char MASK = '*';

	private PiiMasker() {
	}

	/**
	 * 전화번호를 마스킹한다. 가운데만 가리고 앞 3자리와 뒤 4자리는 남긴다.
	 *
	 * <pre>
	 *   010-1234-5678 -> 010-****-5678
	 *   01012345678   -> 010****5678
	 *   0212345678    -> 021***5678
	 * </pre>
	 *
	 * <p>구분자(-)의 위치는 원본을 그대로 따른다. 숫자만 세어 가릴 구간을 정하므로
	 * 하이픈이 있든 없든 같은 자리가 남는다.
	 *
	 * <p>숫자가 8자리 미만이면 앞 3자리와 뒤 4자리를 동시에 남길 수 없어 전부 가린다.
	 * 남길 자리를 억지로 줄이면 짧은 번호일수록 더 많이 드러나는 역전이 생긴다.
	 */
	public static String maskPhone(String phone) {
		if (isBlank(phone)) {
			return UNKNOWN;
		}

		int digitCount = countDigits(phone);

		if (digitCount < 7) {
			return maskAllDigits(phone);
		}

		// 앞 3자리, 뒤 4자리를 남긴다. 그 사이 숫자만 가린다.
		return maskDigitsBetween(phone, 3, digitCount - 4);
	}

	/**
	 * 이메일을 마스킹한다. 로컬 파트 앞 2자리만 남기고 도메인은 그대로 둔다.
	 *
	 * <pre>
	 *   hong@test.com -> ho**@test.com
	 *   ab@test.com   -> a*@test.com
	 *   a@test.com    -> *@test.com
	 * </pre>
	 *
	 * <p>도메인을 남기는 이유는 그 자체로 개인을 특정하지 않으면서
	 * "어느 경로로 가입한 사용자인지" 정도의 정보는 운영에 쓰이기 때문이다.
	 *
	 * <p>{@code @} 가 없으면 이메일 형식이 아니므로 전부 가린다. 로컬 파트로 간주해
	 * 앞 2자리를 남기면, 형식이 아닌 값(예: 전화번호가 잘못 들어온 경우)이 노출된다.
	 */
	public static String maskEmail(String email) {
		if (isBlank(email)) {
			return UNKNOWN;
		}

		int atIndex = email.indexOf('@');

		if (atIndex < 0) {
			return repeat(MASK, email.length());
		}

		String local = email.substring(0, atIndex);
		String domain = email.substring(atIndex);

		// 로컬 파트가 3자 이상일 때만 2자를 남긴다. 2자면 1자, 1자면 0자만 남겨
		// 짧을수록 더 드러나는 일이 없게 한다.
		int keep = Math.min(2, Math.max(0, local.length() - 1));

		return local.substring(0, keep) + repeat(MASK, local.length() - keep) + domain;
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private static int countDigits(String value) {
		int count = 0;

		for (int i = 0; i < value.length(); i++) {
			if (Character.isDigit(value.charAt(i))) {
				count++;
			}
		}

		return count;
	}

	/** 구분자는 그대로 두고 숫자만 전부 가린다. */
	private static String maskAllDigits(String value) {
		return maskDigitsBetween(value, 0, countDigits(value));
	}

	/**
	 * 숫자만 세어 {@code [fromDigitIndex, toDigitIndex)} 구간을 가린다.
	 * 구분자는 위치와 개수를 그대로 유지한다.
	 */
	private static String maskDigitsBetween(String value, int fromDigitIndex, int toDigitIndex) {
		StringBuilder masked = new StringBuilder(value.length());
		int digitIndex = 0;

		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);

			if (!Character.isDigit(c)) {
				masked.append(c);
				continue;
			}

			masked.append(digitIndex >= fromDigitIndex && digitIndex < toDigitIndex ? MASK : c);
			digitIndex++;
		}

		return masked.toString();
	}

	private static String repeat(char c, int count) {
		return count <= 0 ? "" : String.valueOf(c).repeat(count);
	}
}
