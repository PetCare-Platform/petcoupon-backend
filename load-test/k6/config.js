// =====================================================================
// k6 부하 테스트 공통 설정
//
// 대상 서버 · 쿠폰 · 규모를 전부 환경변수로 받는다.
// 로컬 소규모 확인과 AWS 최종 측정에서 스크립트를 고치지 않고 값만 바꿔 쓰기 위함이다.
// =====================================================================

function num(name, fallback) {
	const raw = __ENV[name];
	if (raw === undefined || raw === '') {
		return fallback;
	}
	const parsed = Number(raw);
	if (!Number.isFinite(parsed)) {
		throw new Error(`${name} 는 숫자여야 합니다. 받은 값: ${raw}`);
	}
	return parsed;
}

function bool(name, fallback) {
	const raw = __ENV[name];
	if (raw === undefined || raw === '') {
		return fallback;
	}
	const normalized = String(raw).trim().toLowerCase();
	if (normalized === 'true' || normalized === '1') {
		return true;
	}
	if (normalized === 'false' || normalized === '0') {
		return false;
	}
	throw new Error(`${name} 는 true/false 또는 1/0 이어야 합니다. 받은 값: ${raw}`);
}

// 끝의 슬래시를 떼지 않으면 요청 경로에 // 가 생겨 404 가 난다.
export const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');

export const COUPON_ID = num('COUPON_ID', 1);

// 매 회차 초기화 API 로 되돌릴 총재고.
// 쿠폰을 새로 만들지 않고 스모크(10) → 기본(100) → 중간(500) → 최대(1,000) → 최종(10,000) 을 모두 돌린다.
export const TOTAL_QUANTITY = num('TOTAL_QUANTITY', 10000);

// smoke : 스크립트가 도는지만 확인 (10건)
// burst : 재고보다 많은 요청이 한꺼번에 몰리는 선착순 상황 — 본 측정
// rate  : 초당 요청 수를 고정해 처리량 한계를 보는 측정
export const SCENARIO = __ENV.SCENARIO || 'smoke';

// burst 총 요청 수 = VUS * ITERATIONS_PER_VU.
// 목표가 "동시 사용자 20,000명"이므로 VU 20,000개가 각각 한 번씩 요청한다.
// 2,000 VU × 10회는 총 20,000건일 뿐 동시 사용자는 최대 2,000명이다.
export const VUS = num('VUS', 20000);
export const ITERATIONS_PER_VU = num('ITERATIONS_PER_VU', 1);
export const MAX_DURATION = __ENV.MAX_DURATION || '10m';

// rate 시나리오용
export const RATE = num('RATE', 1000);
export const DURATION = __ENV.DURATION || '30s';
// arrival-rate 실행에 필요한 VU 용량. RATE와 분리해 응답 지연에 맞춰 조정한다.
export const RATE_PRE_ALLOCATED_VUS = num('RATE_PRE_ALLOCATED_VUS', RATE);
export const RATE_MAX_VUS = num('RATE_MAX_VUS', Math.max(RATE_PRE_ALLOCATED_VUS, VUS));

// 요청마다 서로 다른 회원을 쓴다.
// uk_issue_coupon_user(coupon_id, user_id) 때문에 같은 회원의 두 번째 신청은 발급되지 않는다.
// 회원을 재사용하면 "요청 2만 건 중 실제 후보는 1만 명"이 되어 측정이 왜곡된다.
//
// 회원 ID 는 연속이 아니다. 더미 100만 건을 넣을 때 auto_increment 가 띄엄띄엄 올라가고
// 관리자 계정도 사이에 끼어 있어서, 시작값에 1씩 더해 쓰면 없는 회원으로 404 가 난다.
// 그래서 실제 ID 목록을 파일에서 읽는다. 만드는 방법은 README 의 "회원 ID 목록" 참고.
export const MEMBER_IDS_FILE = __ENV.MEMBER_IDS_FILE || './members.csv';

// k6 를 여러 대에서 동시에 돌릴 때 회원 구간과 멱등키가 겹치지 않도록 인스턴스마다 다르게 준다.
// 1번 기기 INSTANCE_INDEX=0, 2번 기기 INSTANCE_INDEX=1 …
export const INSTANCE_INDEX = num('INSTANCE_INDEX', 0);
export const INSTANCE_STRIDE = num('INSTANCE_STRIDE', 100000);

// 멱등키 접두사.
// 같은 값으로 다시 돌리면 이전 회차 키와 겹친다. 초기화(RESET=true)를 하면 키가 지워지므로 괜찮지만,
// RESET=false 로 이어 돌릴 때는 반드시 새 값을 준다.
export const RUN_ID = __ENV.RUN_ID || 'local';

// setup 에서 초기화 API 를 호출할지.
// 이미 초기화한 상태에서 여러 대로 나눠 쏠 때는 1번 기기만 true 로 둔다.
export const RESET = bool('RESET', true);

// 값을 안 주면 null. 부하 측정에는 쓰지 않고 통합 테스트 전용 모드를 켜는 스위치라,
// "기본값"이 아니라 "꺼짐"이 필요하기 때문에 num()/bool() 과 다르게 처리한다.
function optionalNum(name) {
	const raw = __ENV[name];
	if (raw === undefined || raw === '') {
		return null;
	}
	return num(name, null);
}

// ---------------------------------------------------------------------
// 통합 테스트 C 구간 전용 모드 (TC-42 · TC-43)
//
// 부하 측정은 "요청마다 다른 회원 · 다른 멱등키"가 전제다. 아래 두 값은 그 전제를
// 일부러 깨서 중복 요청 처리를 검증하는 용도이므로, 부하 측정에서는 절대 주지 않는다.
// ---------------------------------------------------------------------

// TC-42: 같은 사용자가 동시에 여러 번 신청. 모든 요청이 이 회원으로 나간다.
// 기대 결과는 발급 1건뿐이고 나머지는 Lua 판정에서 ALREADY_APPLIED 로 걸러진다.
export const FIXED_USER_ID = optionalNum('FIXED_USER_ID');

// TC-43: 동일 멱등키 재전송. 모든 요청이 이 키를 쓴다.
// 기대 결과는 발급 1건 · 재고 1 차감이며, 두 번째부터는 처리중(409-5)이거나
// 최초 응답이 그대로 재현된다(202).
export const FIXED_IDEMPOTENCY_KEY = __ENV.FIXED_IDEMPOTENCY_KEY || null;
