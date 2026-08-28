#!/bin/bash
#
# 시연·부하 테스트용 이벤트 + 쿠폰 데이터 (#181)
#
# 이벤트 4개와 각 이벤트당 쿠폰 1개를 만든다. 전부 진행 중이라 오픈 시각이 지나면
# 공개 목록에 네 개가 다 뜬다. 마지막에 부하 테스트 대상 COUPON_ID 를 출력한다.
#
#   ./load-test/scripts/setup-demo-coupon.sh
#   MAIN_TOTAL_QUANTITY=1000 ./load-test/scripts/setup-demo-coupon.sh
#   BASE_URL=http://10.0.1.20:8080 ./load-test/scripts/setup-demo-coupon.sh
#
# 만들어지는 것 (전부 진행 중)
#   ① 선착순 반려견 무료 검진   종합검진 무료 (100%)      재고 적음   ← 부하 테스트·시연 대상
#   ② 검진 할인 쿠폰            검진비 30% 할인           재고 넉넉
#   ③ 반려동물 용품 할인        사료 5,000원 할인         재고 넉넉
#   ④ 여름 예방접종             예방접종 20% 할인         재고 넉넉
#
#   ①만 재고를 요청 수보다 적게 잡아 경쟁이 실제로 일어나게 한다. 나머지는 넉넉히 둬서
#   "그냥 받아가는 쿠폰"으로 보이고, 목록 화면을 채우는 역할을 한다.
#
#   종료된 이벤트는 넣지 않는다 — 공개 목록(GET /events)이 OPEN 만 반환하고 공개 상세도
#   OPEN 이 아니면 EVENT404-1 로 막아서, CLOSED 이벤트는 프론트에 "종료됨"으로 뜨는 게
#   아니라 아예 안 보인다. 넣어봐야 화면에 없는 데이터가 된다.
#   (관리자 목록 GET /admin/events 는 상태 필터가 없어 전부 보인다)
#
# 채워지는 테이블
#   event                  createEvent()
#   event_status_history   createEvent() 가 NONE->SCHEDULED 를 남기고, 이후 상태 스케줄러가
#                          SCHEDULED->OPEN 전이를 추가한다
#   coupon                 createCoupon()
#   coupon_stock           createCoupon() 이 같은 트랜잭션에서
#   Redis 재고 키          createCoupon() (#180 이후)
#
#   coupon_issue / coupon_issue_history / idempotency_key / issue_message / notification_log
#   은 실제로 발급해야 생긴다 — 미리 넣으면 "이미 발급된 상태"로 시작해 시연할 게 없어지므로
#   일부러 만들지 않는다.
#
# 왜 SQL 이 아니라 API 인가
#   쿠폰은 coupon 과 coupon_stock 두 테이블에 걸쳐 있고 createCoupon() 이 둘을 한 트랜잭션에서
#   만든다(#180 이후로는 Redis 재고 키까지). 이벤트도 createEvent() 가 NONE->SCHEDULED 이력을
#   직접 남긴다. SQL 로 넣으면 이것들이 조용히 빠지고, 조회는 되는데 발급하는 순간 깨진다
#   (통합 테스트 TC-73 이 그 상태를 일부러 만들어 DLQ 적재를 유도한 케이스다).
#
# 실행 전
#   docker compose --profile kafka up -d      # Kafka 는 profiles 로 묶여 있어 이 옵션이 필요하다
#   ./gradlew bootRun
#
# 여러 번 돌리면 그때마다 새로 생긴다. 회차 되돌리기는 이 스크립트가 아니라 초기화 API 다.
#
set -u

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_AUTH_CODE="${ADMIN_AUTH_CODE:-local-dev-admin-auth-code}"
MAIN_TOTAL_QUANTITY="${MAIN_TOTAL_QUANTITY:-10000}"

MYSQL_CONTAINER="${MYSQL_CONTAINER:-petcoupon-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root}"
MYSQL_DATABASE="${MYSQL_DATABASE:-petcoupon}"

# 이벤트 오픈까지 남길 시간(분).
#
# 0 으로 두면 안 된다. 쿠폰 생성은 이벤트가 SCHEDULED 일 때만 허용되는데
# (CouponServiceImpl.validateEventStatus), 상태 스케줄러가 매분 돌면서 openAt 이 지난
# 이벤트를 OPEN 으로 바꾼다. openAt 을 현재 시각으로 잡으면 쿠폰을 만들기도 전에
# OPEN 이 되어 COUPON400-1 로 막힐 수 있다.
OPEN_DELAY_MIN="${OPEN_DELAY_MIN:-3}"
CLOSE_AFTER_DAY="${CLOSE_AFTER_DAY:-30}"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

# ---------------------------------------------------------------------
# 응답 파싱 — 팀 로컬(Git Bash)에 jq 가 없어서 sed 로 뽑는다.
# 중첩 없는 단순 필드에만 쓴다.
# ---------------------------------------------------------------------
json_value() {
	sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"\{0,1\}\([^,\"}]*\)\"\{0,1\}.*/\1/p" <<< "$2" | head -1
}

die() {
	echo >&2
	echo "실패: $1" >&2
	[ $# -ge 2 ] && echo "  응답: $2" >&2
	exit 1
}

# 한글이 든 JSON 은 --data-binary "$문자열" 로 넘기면 인코딩이 깨져 COMMON400-2 가 난다(실측).
# 파일에 쓰고 @파일 로 넘긴다.
send_json() {
	local method="$1" url="$2" body="$3" file="$WORK_DIR/req.json"
	shift 3
	printf '%s' "$body" > "$file"
	curl -s -X "$method" "$url" \
		-H "Content-Type: application/json; charset=UTF-8" \
		"$@" \
		--data-binary "@$file"
}

mysql_exec() {
	docker exec "$MYSQL_CONTAINER" mysql \
		--default-character-set=utf8mb4 -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" \
		"$MYSQL_DATABASE" -e "$1" 2>/dev/null
}

# GNU date 기준. Git Bash·리눅스 모두 동작한다.
OPEN_AT="$(date -d "+$OPEN_DELAY_MIN minutes" +"%Y-%m-%dT%H:%M:%S")"
CLOSE_AT="$(date -d "+$CLOSE_AFTER_DAY days" +"%Y-%m-%dT%H:%M:%S")"

echo "대상       $BASE_URL"
echo "발급 시작  $OPEN_AT  (${OPEN_DELAY_MIN}분 뒤)"
echo "발급 종료  $CLOSE_AT"
echo

RES="$(send_json POST "$BASE_URL/admin/auth/sessions" "{\"authCode\": \"$ADMIN_AUTH_CODE\"}")"
TOKEN="$(json_value token "$RES")"
[ -n "$TOKEN" ] || die "관리자 세션 발급" "$RES"
echo "관리자 세션 발급 완료"
echo

# ---------------------------------------------------------------------
# 이벤트 + 쿠폰 한 쌍
#
#   $1 이벤트명  $2 이벤트 설명  $3 쿠폰명
#   $4 할인 유형(FIXED_AMOUNT|RATE)  $5 할인 값  $6 최소 주문 금액
#   $7 최대 할인 금액(RATE 전용, 없으면 null)  $8 총재고
#
#   발급 기간은 이벤트 기간 안에 있어야 하므로(COUPON400-3) 같은 시각을 그대로 쓴다.
#   FIXED_AMOUNT 에 maxDiscountAmount 를 주면 COUPON400-5,
#   RATE 는 할인 값이 100 을 넘으면 COUPON400-4 로 막힌다.
#
#   결과는 CREATED_EVENT_ID / CREATED_COUPON_ID 에 담는다.
# ---------------------------------------------------------------------
create_pair() {
	local event_name="$1" event_desc="$2" coupon_name="$3"
	local discount_type="$4" discount_value="$5" min_order="$6" max_discount="$7" quantity="$8"
	local res event_id coupon_id initialized remaining

	res="$(send_json POST "$BASE_URL/admin/events" "$(cat <<EOF
{
  "name": "$event_name",
  "description": "$event_desc",
  "openAt": "$OPEN_AT",
  "closeAt": "$CLOSE_AT"
}
EOF
)" -H "X-ADMIN-KEY: $TOKEN")"

	event_id="$(json_value eventId "$res")"
	[ -n "$event_id" ] || die "이벤트 생성 ($event_name)" "$res"

	res="$(send_json POST "$BASE_URL/admin/events/$event_id/coupons" "$(cat <<EOF
{
  "name": "$coupon_name",
  "discountType": "$discount_type",
  "discountValue": $discount_value,
  "minOrderAmount": $min_order,
  "maxDiscountAmount": $max_discount,
  "issueStartAt": "$OPEN_AT",
  "issueEndAt": "$CLOSE_AT",
  "validDays": 30,
  "totalQuantity": $quantity
}
EOF
)" -H "X-ADMIN-KEY: $TOKEN")"

	coupon_id="$(json_value couponId "$res")"
	[ -n "$coupon_id" ] || die "쿠폰 생성 ($coupon_name)" "$res"

	# #180 이후 createCoupon() 이 Redis 재고 키까지 세우므로 따로 초기화하지 않는다.
	# 대신 실제로 채워졌는지 확인한다 — initialized 가 false 면 쿠폰은 있는데 발급이 전건
	# 실패하는 상태이고, remainingQuantity 는 총재고로 나와 "재고 가득"으로 보인다(TC-17).
	res="$(curl -s "$BASE_URL/coupons/$coupon_id/status")"
	initialized="$(json_value initialized "$res")"
	remaining="$(json_value remainingQuantity "$res")"

	[ "$initialized" = "true" ] \
		|| die "$coupon_name (couponId=$coupon_id) 재고 키 미초기화 — 이 상태로 발급하면 전건 실패한다" "$res"

	echo "  eventId=$event_id  couponId=$coupon_id  재고=$remaining"

	CREATED_EVENT_ID="$event_id"
	CREATED_COUPON_ID="$coupon_id"
}

# ---------------------------------------------------------------------
# 이벤트 4개 + 쿠폰 4개 (전부 진행 중)
#
#   ①만 재고를 적게 잡는다. 부하 테스트에서 요청 수보다 재고가 적어야 경쟁이 생기고,
#   "재고 10,000에 20,000명이 몰렸을 때 정확히 10,000장만 나갔는가"를 볼 수 있다.
#   나머지는 재고를 넉넉히 둬서 목록 화면을 채우는 역할만 한다.
# ---------------------------------------------------------------------
echo "① 선착순 반려견 무료 검진 이벤트"
create_pair "선착순 반려견 무료 검진 이벤트" "선착순 무료 검진 — 부하 테스트·시연 대상" \
	"반려견 종합검진 무료 쿠폰" "RATE" 100 0 "null" "$MAIN_TOTAL_QUANTITY"
MAIN_COUPON_ID="$CREATED_COUPON_ID"
FIRST_EVENT_ID="$CREATED_EVENT_ID"
echo

echo "② 검진 할인 쿠폰 이벤트"
create_pair "검진 할인 쿠폰 이벤트" "검진비 할인" \
	"반려동물 검진비 30% 할인 쿠폰" "RATE" 30 30000 50000 50000
echo

echo "③ 반려동물 용품 할인 이벤트"
create_pair "반려동물 용품 할인 이벤트" "사료·용품 할인" \
	"반려동물 사료 5,000원 할인 쿠폰" "FIXED_AMOUNT" 5000 30000 "null" 50000
echo

echo "④ 여름 예방접종 이벤트"
create_pair "여름 예방접종 이벤트" "예방접종 할인" \
	"반려동물 예방접종 20% 할인 쿠폰" "RATE" 20 20000 30000 30000
LAST_EVENT_ID="$CREATED_EVENT_ID"

# ---------------------------------------------------------------------
# 결과
# ---------------------------------------------------------------------
echo
mysql_exec "
SELECT e.event_id, e.name AS 이벤트, e.status AS 이벤트상태,
       c.coupon_id, c.name AS 쿠폰, c.status AS 쿠폰상태,
       s.total_quantity AS 총재고,
       (SELECT COUNT(*) FROM event_status_history h WHERE h.event_id = e.event_id) AS 이력
  FROM event e
  JOIN coupon c ON c.event_id = e.event_id
  JOIN coupon_stock s ON s.coupon_id = c.coupon_id
 WHERE e.event_id BETWEEN $FIRST_EVENT_ID AND $LAST_EVENT_ID
 ORDER BY e.event_id;
"

cat <<EOF

======================================================
  부하 테스트 대상  COUPON_ID = $MAIN_COUPON_ID
======================================================

  진행 중 쿠폰의 상태는 ${OPEN_DELAY_MIN}분 뒤 스케줄러가 READY -> ACTIVE 로 바꾼다.
  그때까지 기다렸다가 발급·측정을 시작한다.

  공개 목록(GET /events)은 OPEN 이벤트만 보여주므로, 그 전에는 프론트
  화면에도 안 나온다. #182(발급 기간 검증) 머지 후에는 오픈 전 발급 요청이
  COUPON400-13 으로 거절되기도 한다.

  k6 실행
    k6 run -e SCENARIO=burst -e COUPON_ID=$MAIN_COUPON_ID \\
      -e TOTAL_QUANTITY=$MAIN_TOTAL_QUANTITY -e VUS=$((MAIN_TOTAL_QUANTITY * 2)) \\
      -e ITERATIONS_PER_VU=1 load-test/k6/issue-coupon.js

  회차 되돌리기 (쿠폰을 새로 만들 필요 없다)
    curl -X POST $BASE_URL/internal/coupons/$MAIN_COUPON_ID/reset \\
      -H "Content-Type: application/json" -d '{"totalQuantity": $MAIN_TOTAL_QUANTITY}'

EOF
