// =====================================================================
// 선착순 쿠폰 발급 부하 테스트
//
// 대상: POST /coupons/{couponId}/issues
//
// 이 스크립트가 재는 것과 재지 않는 것
// -------------------------------------------------------------------
// 발급 API 는 비동기다. 요청은 Redis Stream 에 적재되고, 당첨 여부는
// Consumer 가 나중에 판정한다. 그래서 응답은 재고가 남았든 소진됐든
// 항상 202 Accepted + status="WAITING" 이다.
//
//   k6 가 재는 것  : 접수 성공률, 접수 응답 시간, 타임아웃 · 5xx 발생 여부
//   k6 가 못 재는 것: 누가 당첨됐는지, 발급이 재고를 넘지 않았는지
//
// 따라서 "k6 성공 응답 수 = DB 발급 건수" 는 성립하지 않는다.
// 2만 건을 쏘면 k6 는 2만 건 성공, DB 에는 재고만큼만 남는 것이 정상이다.
// 발급 정합성은 부하 종료 후 load-test/sql/verify_issue_result.sql 로 확인한다.
// =====================================================================

import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';

import * as cfg from './config.js';

// http_req_failed 는 4xx 를 뭉뚱그려 실패로 세기 때문에,
// "재시도하라(409)" 와 "설정이 틀렸다(404)" 와 "서버가 터졌다(5xx)" 를 나눠서 센다.
const accepted = new Counter('issue_accepted');
const conflict = new Counter('issue_conflict');
const notFound = new Counter('issue_not_found');
// 400. 서버가 아니라 요청이 잘못된 것 — 멱등키 누락·길이 초과 등 스크립트 설정 문제라 따로 센다.
const badRequest = new Counter('issue_bad_request');
const serverError = new Counter('issue_server_error');
// 응답을 아예 못 받은 요청(연결 거절 · 리셋 · 타임아웃). k6 는 이때 status 를 0 으로 준다.
// 서버가 500 을 돌려준 것과 원인이 완전히 다르므로 따로 센다.
const requestError = new Counter('issue_request_error');
const acceptRate = new Rate('issue_accept_rate');
// 접수된 응답이 API 계약(status="WAITING")을 지켰는지만 따로 센다. 접수 성공률과 분리하는 이유는
// 접수는 1% 실패를 허용하지만(issue_accept_rate) 계약 위반은 한 건도 허용하지 않기 때문이다.
const contractRate = new Rate('issue_contract_ok');
const acceptedDuration = new Trend('issue_accepted_duration', true);

// 실제로 존재하는 회원 ID 목록.
// SharedArray 라 VU 를 20,000 개 띄워도 메모리에는 한 벌만 올라간다.
const MEMBERS = new SharedArray('members', function () {
	let raw;
	try {
		raw = open(cfg.MEMBER_IDS_FILE);
	} catch (e) {
		throw new Error(
			'회원 ID 목록을 열 수 없습니다: ' + cfg.MEMBER_IDS_FILE + '\n' +
				'README 의 "회원 ID 목록 만들기" 항목대로 먼저 만들어 주세요.',
		);
	}
	// 숫자만 남긴다. 헤더 줄이나 CRLF 가 섞여 들어오면 없는 회원으로 404 가 난다.
	const ids = [];
	const lines = raw.split('\n');
	for (let i = 0; i < lines.length; i++) {
		const line = lines[i].trim();
		if (line !== '' && /^[0-9]+$/.test(line)) {
			ids.push(Number(line));
		}
	}
	if (ids.length === 0) {
		throw new Error('회원 ID 목록이 비어 있습니다: ' + cfg.MEMBER_IDS_FILE);
	}
	return ids;
});

const SCENARIOS = {
	smoke: {
		executor: 'per-vu-iterations',
		vus: 10,
		iterations: 1,
		maxDuration: '1m',
	},
	// per-vu-iterations 는 모든 VU 가 동시에 출발한다. 선착순이 몰리는 순간을 재현하려는 것.
	burst: {
		executor: 'per-vu-iterations',
		vus: cfg.VUS,
		iterations: cfg.ITERATIONS_PER_VU,
		maxDuration: cfg.MAX_DURATION,
	},
	// 서버가 느려져도 요청 속도를 유지한다. 처리량 한계를 볼 때 쓴다.
	rate: {
		executor: 'constant-arrival-rate',
		rate: cfg.RATE,
		timeUnit: '1s',
		duration: cfg.DURATION,
		preAllocatedVUs: cfg.RATE_PRE_ALLOCATED_VUS,
		maxVUs: cfg.RATE_MAX_VUS,
	},
};

// 서버의 @Size(max = 64) 와 같은 값. 넘으면 400 이라 setup 에서 미리 막는다.
const IDEMPOTENCY_KEY_MAX_LENGTH = 64;

// 키 생성 규칙을 한곳에 둔다. setup 의 길이 검사와 실제 요청이 같은 규칙을 써야 검사가 의미를 갖는다.
function buildIdempotencyKey(seq) {
	return cfg.RUN_ID + '-' + cfg.INSTANCE_INDEX + '-' + seq;
}

// k6 duration 표기('30s', '1h30m', '500ms')를 초로 바꾼다. rate 시나리오의 필요 회원 수 계산에 쓴다.
function durationToSeconds(duration) {
	const raw = String(duration).trim();
	const tokenPattern = /(\d+(?:\.\d+)?)(ms|s|m|h)/g;
	const unitSeconds = { ms: 0.001, s: 1, m: 60, h: 3600 };
	let seconds = 0;
	let consumed = '';
	let matched;

	while ((matched = tokenPattern.exec(raw)) !== null) {
		consumed += matched[0];
		seconds += Number(matched[1]) * unitSeconds[matched[2]];
	}
	if (consumed !== raw || seconds <= 0) {
		throw new Error('DURATION 형식이 올바르지 않습니다. 예: 30s, 1h30m, 500ms. 받은 값: ' + duration);
	}
	return Math.ceil(seconds);
}

const scenario = SCENARIOS[cfg.SCENARIO];
if (!scenario) {
	throw new Error(
		'SCENARIO 는 ' + Object.keys(SCENARIOS).join(' | ') + ' 중 하나여야 합니다. 받은 값: ' + cfg.SCENARIO,
	);
}

const thresholds = {
	// 404 는 쿠폰 ID 나 회원 목록이 잘못된 것 — 측정이 아니라 설정 문제다.
	issue_not_found: ['count==0'],
	issue_bad_request: ['count==0'],
	issue_server_error: ['count==0'],
	issue_request_error: ['count==0'],
	// VU가 부족해 목표 RATE를 못 맞추면 성공으로 오독하지 않고 테스트를 실패시킨다.
	dropped_iterations: ['count==0'],
	// check() 는 요약에만 찍히고 종료 코드에 반영되지 않는다. 임계값을 걸어야
	// 응답 계약이 깨졌을 때 k6 가 실패로 끝난다. 접수 성공률과 달리 한 건도 허용하지 않는다.
	// 접수(202)된 응답만 세므로 409 가 섞여도 이 지표는 흔들리지 않는다.
	issue_contract_ok: ['rate==1'],
	'http_req_duration{expected_response:true}': ['p(95)<1000', 'p(99)<3000'],
};

// TC-43(동일 멱등키 재전송)에서는 409 와 비-202 가 기대 결과다.
//   두 번째 요청부터 서버는 "처리 중"(COUPON409-5)을 주거나 최초 응답을 그대로 재현한다.
// 그래서 아래 두 임계값을 그대로 두면 성공 시나리오가 실패로 판정된다.
// 대신 발급이 1건인지는 k6 가 아니라 verify_issue_result.sql 1번 항목으로 확인한다.
if (cfg.FIXED_IDEMPOTENCY_KEY === null) {
	// 접수 자체가 실패하면 그 뒤 정합성 검증이 의미가 없다.
	thresholds.issue_accept_rate = ['rate>0.99'];
	// 409 는 멱등키가 겹쳤다는 뜻이다. 요청마다 고유한 키를 만드는 이상 나올 수 없고,
	// 나왔다면 키 생성 규칙이 깨진 것이라 그 회차 측정은 믿을 수 없다.
	thresholds.issue_conflict = ['count==0'];
}

export const options = {
	scenarios: { [cfg.SCENARIO]: scenario },
	thresholds: thresholds,
};

export function setup() {
	const offset = cfg.INSTANCE_INDEX * cfg.INSTANCE_STRIDE;
	if (cfg.SCENARIO === 'rate' && cfg.RATE_MAX_VUS < cfg.RATE_PRE_ALLOCATED_VUS) {
		throw new Error(
			'RATE_MAX_VUS는 RATE_PRE_ALLOCATED_VUS 이상이어야 합니다. ' +
				'preAllocated=' + cfg.RATE_PRE_ALLOCATED_VUS + ' max=' + cfg.RATE_MAX_VUS,
		);
	}

	// 멱등키 유니크 제약은 (user_id, idempotency_key) 다. 키만 고정하고 회원이 제각각이면
	// 서버는 서로 다른 요청으로 보고 전건을 발급한다 — 실측으로 확인했다(키 고정 3건 → 발급 3건,
	// idempotency_key 에 같은 키가 3행). TC-43 은 "같은 요청의 재전송"이므로 회원도 같아야 한다.
	// 그냥 두면 통과한 것처럼 보이는 잘못된 결과가 나오므로 여기서 막는다.
	if (cfg.FIXED_IDEMPOTENCY_KEY !== null && cfg.FIXED_USER_ID === null) {
		throw new Error(
			'FIXED_IDEMPOTENCY_KEY 는 FIXED_USER_ID 와 함께 줘야 합니다.\n' +
				'멱등키는 (user_id, idempotency_key) 로만 유니크해서, 회원이 다르면 같은 키를 써도\n' +
				'서로 다른 요청으로 처리돼 전건이 발급됩니다. 재전송 검증이 되지 않습니다.\n' +
				'예: -e FIXED_USER_ID=878 -e FIXED_IDEMPOTENCY_KEY=tc43-key',
		);
	}

	// 시나리오마다 보낼 요청 수. rate 는 초당 요청 수 × 지속 시간으로 계산한다.
	const requiredMembers = cfg.SCENARIO === 'rate'
		? cfg.RATE * durationToSeconds(cfg.DURATION)
		: scenario.vus * scenario.iterations;

	// TC-42 는 "같은 회원이 동시에 여러 번 신청하면 1건만 발급되는가" 를 보는 시나리오라,
	// 회원 재사용이 결함이 아니라 검증 대상 그 자체다. 아래 두 검사는 회원을 돌려쓰는 걸
	// 막으려고 둔 것이므로 이 모드에서는 건너뛴다. 그대로 두면 시나리오를 실행할 수 없다.
	if (cfg.FIXED_USER_ID !== null) {
		console.log(
			'[TC-42 모드] 모든 요청을 userId=' + cfg.FIXED_USER_ID + ' 로 보냅니다. ' +
				'회원 수 검사를 건너뜁니다 — 발급은 1건만 나와야 정상입니다.',
		);
	} else {

	// 인스턴스별 오프셋은 INSTANCE_STRIDE 단위로 나뉘므로, 한 인스턴스가 그보다 많은 회원을
	// 사용하면 다음 인스턴스의 구간과 겹친다. 같은 회원이 재사용되면 1인 1매 제약으로
	// 발급 파이프라인을 타지 않아 다중 k6 결과가 왜곡된다.
	if (cfg.INSTANCE_STRIDE < requiredMembers) {
		throw new Error(
			'INSTANCE_STRIDE가 인스턴스별 필요 회원 수보다 작습니다. ' +
				'stride=' + cfg.INSTANCE_STRIDE + ' requiredMembers=' + requiredMembers + '\n' +
				'다중 k6 실행 시 회원 구간이 겹치므로 INSTANCE_STRIDE를 requiredMembers 이상으로 늘리세요.',
		);
	}

	// 회원을 돌려쓰면 두 번째부터는 1인 1매 제약에 걸려 판정 단계에서 탈락한다.
	// 탈락한 요청은 Outbox·Kafka·DB 를 거치지 않고 끝나므로, 처리량이 실제보다 좋게 나온다.
	// 파이프라인 전 구간을 재려면 요청 하나당 회원 하나가 반드시 있어야 한다.
	if (offset + requiredMembers > MEMBERS.length) {
		throw new Error(
			'회원이 모자랍니다. 필요=' + (offset + requiredMembers) + ' 보유=' + MEMBERS.length + '\n' +
				'회원을 돌려쓰면 중복 신청이 되어 발급 경로를 타지 않고 탈락합니다.\n' +
				'그 요청들은 파이프라인을 거치지 않으므로 처리량이 실제보다 높게 측정됩니다.\n' +
				'회원 ID 목록을 늘리거나(README 4번) 요청 수를 줄이세요.',
		);
	}

	}

	// 서버가 Idempotency-Key 를 64자로 제한한다. 넘으면 400 인데, 부하 중에 전건 400 이 뜨면
	// 원인을 찾느라 회차를 통째로 버리게 된다. 가장 긴 키를 미리 만들어 여기서 막는다.
	// 고정 키 모드(TC-43)에서는 그 키 자체가 유일하게 쓰이는 키다.
	const longestKey = cfg.FIXED_IDEMPOTENCY_KEY !== null
		? cfg.FIXED_IDEMPOTENCY_KEY
		: buildIdempotencyKey(offset + Math.max(requiredMembers, 1) - 1);
	if (longestKey.length > IDEMPOTENCY_KEY_MAX_LENGTH) {
		throw new Error(
			'Idempotency-Key 가 서버 제한(' + IDEMPOTENCY_KEY_MAX_LENGTH + '자)을 넘습니다. ' +
				'길이=' + longestKey.length + ' 예시="' + longestKey + '"\n' +
				'RUN_ID 를 짧게 줄이세요.',
		);
	}

	console.log(
		'대상=' + cfg.BASE_URL + ' 쿠폰=' + cfg.COUPON_ID +
			' 시나리오=' + cfg.SCENARIO + ' 회원=' + MEMBERS.length + '명(offset ' + offset + ')',
	);

	if (!cfg.RESET) {
		console.warn(
			'RESET=false — 초기화를 건너뜁니다. RUN_ID 를 이전 회차와 다르게 주지 않으면 ' +
				'멱등키가 겹쳐 실제 발급 없이 이전 응답이 그대로 재현됩니다.',
		);
		return {};
	}

	// 초기화 API 는 DB(발급 · 이력 · 멱등키 · Outbox · 검증리포트)와 Redis 발급 상태를 되돌린다.
	const res = http.post(
		cfg.BASE_URL + '/internal/coupons/' + cfg.COUPON_ID + '/reset',
		JSON.stringify({ totalQuantity: cfg.TOTAL_QUANTITY }),
		{ headers: { 'Content-Type': 'application/json' }, timeout: '300s' },
	);

	// 409 는 앞 회차 메시지가 파이프라인에 남아 거절된 것이다. 이 상태로 쏘면 지난 회차 신청이
	// 뒤늦게 확정되며 이번 회차 재고를 깎아, 측정 결과를 믿을 수 없게 된다.
	if (res.status === 409) {
		throw new Error(
			'앞 회차 메시지가 아직 처리 중이라 초기화가 거절됐습니다.\n' +
				'큐가 빌 때까지 기다린 뒤 다시 실행하세요. 확인 방법은 load-test/README.md 의 "초기화" 항목 참고.\n' +
				'응답: ' + res.body,
		);
	}

	if (res.status !== 200) {
		throw new Error('초기화 API 실패: status=' + res.status + ' body=' + res.body);
	}

	const result = res.json('result');

	// redisStock 은 초기화 후 Redis 에서 다시 읽은 값이다. 여기서 안 보면 Redis 초기화가 실패한 채로
	// 부하를 쏘게 되는데, 그때는 Lua 가 전건을 STOCK_NOT_INITIALIZED 로 거절한다.
	// 그래도 응답은 WAITING 이라 k6 는 전부 성공으로 집계한다 — SQL 을 돌려야 발급 0건인 걸 알게 된다.
	if (result.redisStock !== cfg.TOTAL_QUANTITY) {
		throw new Error(
			'Redis 재고 초기화가 끝나지 않았습니다. 기대=' + cfg.TOTAL_QUANTITY +
				' 실제=' + result.redisStock + '\n' +
				'이 상태로 쏘면 전건이 거절되는데 응답은 WAITING 이라 k6 요약만으로는 알 수 없습니다.',
		);
	}

	console.log(
		'초기화 완료 — 총재고=' + result.totalQuantity +
			' Redis재고=' + result.redisStock +
			' 지운 발급=' + result.deletedIssues +
			' 이력=' + result.deletedHistories +
			' 멱등키=' + result.deletedIdempotencyKeys +
			' Outbox=' + result.deletedMessages,
	);

	return { totalQuantity: result.totalQuantity };
}

export default function () {
	// 시나리오 전체에서 유일한 순번. VU 가 재사용돼도 요청마다 1씩 증가한다.
	// __VU 만으로 회원을 정하면 같은 VU 의 2회차 요청이 같은 회원이 되어 발급되지 않는다.
	const seq = exec.scenario.iterationInTest;
	const index = cfg.INSTANCE_INDEX * cfg.INSTANCE_STRIDE + seq;

	// setup() 이 rate 를 포함한 모든 시나리오에서 필요한 회원 수를 확보했는지 미리 검사하므로,
	// 여기까지 왔다면 목록을 넘어서는 일은 없다. % 는 그 검사가 뚫렸을 때 배열 밖 접근으로
	// undefined 를 보내는 대신 조용히 앞으로 돌아오게 하는 안전망일 뿐, 회원 재사용은 의도가 아니다.
	// 회원을 돌려쓰면 1인 1매에 걸려 발급 경로를 타지 않고, 그만큼 처리량이 실제보다 높게 나온다.
	//
	// FIXED_USER_ID 가 있으면 그 회원으로만 보낸다(TC-42) — 재사용이 곧 검증 대상이다.
	const userId = cfg.FIXED_USER_ID !== null
		? cfg.FIXED_USER_ID
		: MEMBERS[index % MEMBERS.length];

	// Idempotency-Key 는 (user_id, idempotency_key) 로만 유니크하지만,
	// 회차가 겹쳐 재현 응답이 나오는 사고를 막으려고 요청마다 전역 유일하게 만든다.
	// 64자 제한은 setup 에서 이미 검사했다.
	//
	// FIXED_IDEMPOTENCY_KEY 가 있으면 모든 요청이 같은 키를 쓴다(TC-43) — 재전송 검증용이다.
	const idempotencyKey = cfg.FIXED_IDEMPOTENCY_KEY !== null
		? cfg.FIXED_IDEMPOTENCY_KEY
		: buildIdempotencyKey(index);

	const res = http.post(
		cfg.BASE_URL + '/coupons/' + cfg.COUPON_ID + '/issues',
		JSON.stringify({ userId: userId }),
		{
			headers: {
				'Content-Type': 'application/json',
				// 필수 헤더다. 빠지면 전부 400 으로 거절된다.
				'Idempotency-Key': idempotencyKey,
			},
			// URL 에 쿠폰 ID 가 들어가도 지표가 쪼개지지 않도록 이름을 고정한다.
			tags: { name: 'POST /coupons/:couponId/issues' },
		},
	);

	// 접수 성공은 202 다. 신청이 큐에 실렸다는 뜻이고, 발급 여부는 아직 정해지지 않았다.
	const ok = res.status === 202;
	acceptRate.add(ok);

	if (ok) {
		accepted.add(1);
		acceptedDuration.add(res.timings.duration);
	} else if (res.status === 400) {
		// 요청 자체가 거절됐다. Idempotency-Key 누락·64자 초과, userId 형식 오류 등.
		// 서버 문제가 아니라 스크립트 설정 문제이므로 5xx 와 섞지 않는다.
		badRequest.add(1);
	} else if (res.status === 409) {
		// 처리 중(COUPON409-5) 또는 멱등키 재사용(COUPON409-6).
		// 정상 부하에서는 0 이어야 하고, 값이 크면 키 생성 규칙을 의심한다.
		conflict.add(1);
	} else if (res.status === 404) {
		notFound.add(1);
	} else if (res.status === 0) {
		// 서버까지 닿지 못했다. 부하 생성기 쪽 한계(포트 고갈)일 수도, 서버가 연결을 끊은 것일 수도 있다.
		// error_code 를 태그로 남겨야 요약에서 원인별로 갈라 볼 수 있다.
		requestError.add(1, { error_code: String(res.error_code) });
	} else {
		serverError.add(1, { status: String(res.status) });
	}

	// 비동기라 이 시점에 당첨 · 품절을 알 수 없다. WAITING 이 아니면 API 계약이 바뀐 것이다.
	// 접수에 실패한 응답은 계약을 따질 대상이 아니라 집계에서 뺀다.
	if (ok) {
		contractRate.add(res.body !== null && res.body.indexOf('"WAITING"') !== -1);
	}

	// 두 조건을 한 줄로 합친다. 나눠서 'WAITING' 쪽에 !ok 를 두면 500 · 400 이 쏟아져도
	// 그 줄은 100% 성공으로 찍혀서, 요약만 보는 사람이 정상이라고 오독한다.
	check(res, {
		'접수 202 + status=WAITING': () => ok && res.body !== null && res.body.indexOf('"WAITING"') !== -1,
	});
}

export function teardown() {
	console.log('');
	console.log('접수까지만 측정했습니다. 발급 확정은 비동기라 아직 진행 중일 수 있습니다.');
	console.log('load-test/sql/verify_issue_result.sql 로 정합성을 확인하세요. (README 참고)');
}
