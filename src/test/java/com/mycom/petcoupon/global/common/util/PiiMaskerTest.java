package com.mycom.petcoupon.global.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("PiiMasker")
class PiiMaskerTest {

	@Nested
	@DisplayName("전화번호")
	class Phone {

		@ParameterizedTest(name = "{0} -> {1}")
		@CsvSource({
			"010-1234-5678, 010-****-5678",
			"01012345678,   010****5678",
			"010 1234 5678, 010 **** 5678",
			"02-123-4567,   02-1**-4567",
		})
		@DisplayName("앞 3자리와 뒤 4자리를 남기고 가운데만 가린다")
		void 가운데만_가린다(String raw, String expected) {
			assertThat(PiiMasker.maskPhone(raw)).isEqualTo(expected);
		}

		@Test
		@DisplayName("구분자의 위치와 개수는 원본 그대로 둔다")
		void 구분자는_유지된다() {
			String masked = PiiMasker.maskPhone("010-1234-5678");

			// 하이픈이 사라지면 자릿수를 세는 사람이 헷갈린다.
			assertThat(masked).hasSameSizeAs("010-1234-5678");
			assertThat(masked.charAt(3)).isEqualTo('-');
			assertThat(masked.charAt(8)).isEqualTo('-');
		}

		@ParameterizedTest
		@ValueSource(strings = {"123456", "12-345"})
		@DisplayName("숫자가 7자리 미만이면 전부 가린다")
		void 너무_짧으면_전부_가린다(String raw) {
			// 앞 3 + 뒤 4 를 동시에 남길 수 없다. 남길 자리를 줄이면
			// 짧은 번호일수록 더 많이 드러나는 역전이 생기므로 전부 가린다.
			assertThat(PiiMasker.maskPhone(raw)).doesNotContainPattern("[0-9]");
		}

		@ParameterizedTest
		@NullAndEmptySource
		@ValueSource(strings = {"   "})
		@DisplayName("값이 없으면 null 이 아니라 (없음) 을 돌려준다")
		void 값이_없으면_대체값(String raw) {
			// null 을 그대로 돌려주면 호출부가 저장할 때 recipient_masked 의
			// NOT NULL 제약에 걸려 알림 기록 자체가 실패한다.
			assertThat(PiiMasker.maskPhone(raw)).isEqualTo(PiiMasker.UNKNOWN);
		}

		@Test
		@DisplayName("마스킹 결과가 원본보다 길어지지 않는다")
		void 길이가_늘지_않는다() {
			// recipient_masked 는 length = 100, phone 은 length = 20 이다.
			// 마스킹 때문에 길이가 늘면 저장이 실패할 수 있다.
			String raw = "010-1234-5678";

			assertThat(PiiMasker.maskPhone(raw).length()).isLessThanOrEqualTo(raw.length());
		}
	}

	@Nested
	@DisplayName("이메일")
	class Email {

		@ParameterizedTest(name = "{0} -> {1}")
		@CsvSource({
			"hong@test.com,     ho**@test.com",
			"ab@test.com,       a*@test.com",
			"a@test.com,        *@test.com",
			"seyeon.h@test.com, se******@test.com",
		})
		@DisplayName("로컬 파트만 가리고 도메인은 남긴다")
		void 로컬_파트만_가린다(String raw, String expected) {
			assertThat(PiiMasker.maskEmail(raw)).isEqualTo(expected);
		}

		@Test
		@DisplayName("@ 가 없으면 이메일 형식이 아니므로 전부 가린다")
		void 형식이_아니면_전부_가린다() {
			// 로컬 파트로 간주해 앞 2자리를 남기면, 형식이 아닌 값
			// (예: 전화번호가 잘못 들어온 경우)이 그대로 노출된다.
			assertThat(PiiMasker.maskEmail("01012345678")).isEqualTo("***********");
		}

		@ParameterizedTest
		@NullAndEmptySource
		@ValueSource(strings = {"   "})
		@DisplayName("값이 없으면 (없음) 을 돌려준다")
		void 값이_없으면_대체값(String raw) {
			assertThat(PiiMasker.maskEmail(raw)).isEqualTo(PiiMasker.UNKNOWN);
		}
	}
}
