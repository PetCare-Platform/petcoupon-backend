#!/bin/bash
#
# 시연·부하 테스트용 이벤트 + 쿠폰 만들기 (#181)
#
# 관리자 API 로 이벤트와 쿠폰을 만들고 Redis 재고 키까지 세팅한 뒤 COUPON_ID 를 출력한다.
# 그 값을 k6 의 -e COUPON_ID 로 넘기면 바로 부하를 걸 수 있다.
#
#   ./load-test/scripts/setup-demo-coupon.sh
#   TOTAL_QUANTITY=1000 ./load-test/scripts/setup-demo-coupon.sh
#   BASE_URL=http://10.0.1.20:8080 ./load-test/scripts/setup-demo-coupon.sh
#
# 왜 SQL 이 아니라 API 인가
#   쿠폰은 coupon 과 coupon_stock 두 테이블에 걸쳐 있고 CouponServiceImpl.createCoupon()이
#   둘을 한 트랜잭션에서 만든다. SQL 로 coupon 만 넣으면 조회는 되는데 발급하는 순간 깨진다
#   (통합 테스트 TC-73 이 그 상태를 일부러 만들어 DLQ 적재를 유도한 케이스다).
#   이벤트 상태·발급 기간·할인 정책 검증도 API 를 타야 통과한 데이터가 된다.
#
#   반대로 seed_coupon_issues.sql 이 SQL 인 건 300만 건이고 과거 시각(ENDED)이라 API 로는
#   만들 수 없기 때문이다. 목적이 다르다.
#
# 실행 전
#   docker compose --profile kafka up -d      # Kafka 는 profiles 로 묶여 있어 이 옵션이 필요하다
#   ./gradlew bootRun
#
# 여러 번 돌리면 그때마다 새 이벤트·쿠폰이 생긴다. 회차를 되돌리는 건 이 스크립트가 아니라
# 초기화 API 다 — 맨 아래 안내 참고.
#
set -u

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_AUTH_CODE="${ADMIN_AUTH_CODE:-local-dev-admin-auth-code}"
TOTAL_QUANTITY="${TOTAL_QUANTITY:-10000}"
EVENT_NAME="${EVENT_NAME:-선착순 쿠폰 이벤트}"
COUPON_NAME="${COUPON_NAME:-선착순 할인 쿠폰}"
DISCOUNT_VALUE="${DISCOUNT_VALUE:-3000}"
MIN_ORDER_AMOUNT="${MIN_ORDER_AMOUNT:-10000}"
VALID_DAYS="${VALID_DAYS:-30}"

# 이벤트 오픈까지 남길 시간(분).
#
# 0 으로 두면 안 된다. 쿠폰 생성은 이벤트가 SCHEDULED 일 때만 허용되는데
# (CouponServiceImpl.validateEventStatus), 상태 스케줄러가 매분 돌면서 openAt 이 지난
# 이벤트를 OPEN 으로 바꾼다. openAt 을 현재 시각으로 잡으면 이 스크립트가 쿠폰을 만들기도
# 전에 OPEN 이 되어 COUPON400-1 로 막힐 수 있다.
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
post_json() {
	local url="$1" body="$2" file="$WORK_DIR/req.json"
	shift 2
	printf '%s' "$body" > "$file"
	curl -s -X POST "$url" \
		-H "Content-Type: application/json; charset=UTF-8" \
		"$@" \
		--data-binary "@$file"
}

# GNU date 기준. Git Bash·리눅스 모두 동작한다.
OPEN_AT="$(date -d "+$OPEN_DELAY_MIN minutes" +"%Y-%m-%dT%H:%M:%S")"
CLOSE_AT="$(date -d "+$CLOSE_AFTER_DAY days" +"%Y-%m-%dT%H:%M:%S")"

echo "대상       $BASE_URL"
echo "총재고     $TOTAL_QUANTITY"
echo "발급 시작  $OPEN_AT  (${OPEN_DELAY_MIN}분 뒤)"
echo "발급 종료  $CLOSE_AT"
echo

# ---------------------------------------------------------------------
# 1. 관리자 세션
# ---------------------------------------------------------------------
RES="$(post_json "$BASE_URL/admin/auth/sessions" "{\"authCode\": \"$ADMIN_AUTH_CODE\"}")"
TOKEN="$(json_value token "$RES")"
[ -n "$TOKEN" ] || die "관리자 세션 발급" "$RES"
echo "1/4  관리자 세션 발급"

# ---------------------------------------------------------------------
# 2. 이벤트
# ---------------------------------------------------------------------
RES="$(post_json "$BASE_URL/admin/events" "$(cat <<EOF
{
  "name": "$EVENT_NAME",
  "description": "시연·부하 테스트용",
  "openAt": "$OPEN_AT",
  "closeAt": "$CLOSE_AT"
}
EOF
)" -H "X-ADMIN-KEY: $TOKEN")"

EVENT_ID="$(json_value eventId "$RES")"
[ -n "$EVENT_ID" ] || die "이벤트 생성" "$RES"
echo "2/4  이벤트 생성        eventId=$EVENT_ID"

# ---------------------------------------------------------------------
# 3. 쿠폰
#
#    발급 기간은 이벤트 기간 안에 있어야 한다(COUPON400-3). 그래서 같은 시각을 그대로 쓴다.
#    FIXED_AMOUNT 는 정액이라 maxDiscountAmount 를 넣으면 COUPON400-5 로 막힌다
#    (정률로 바꾸려면 discountType 을 RATE 로 두고 maxDiscountAmount 를 채운다).
# ---------------------------------------------------------------------
RES="$(post_json "$BASE_URL/admin/events/$EVENT_ID/coupons" "$(cat <<EOF
{
  "name": "$COUPON_NAME",
  "discountType": "FIXED_AMOUNT",
  "discountValue": $DISCOUNT_VALUE,
  "minOrderAmount": $MIN_ORDER_AMOUNT,
  "maxDiscountAmount": null,
  "issueStartAt": "$OPEN_AT",
  "issueEndAt": "$CLOSE_AT",
  "validDays": $VALID_DAYS,
  "totalQuantity": $TOTAL_QUANTITY
}
EOF
)" -H "X-ADMIN-KEY: $TOKEN")"

COUPON_ID="$(json_value couponId "$RES")"
[ -n "$COUPON_ID" ] || die "쿠폰 생성" "$RES"
echo "3/4  쿠폰 생성          couponId=$COUPON_ID"

# ---------------------------------------------------------------------
# 4. Redis 재고 키
#
#    이게 빠지면 쿠폰이 있어도 발급이 안 된다. Lua 가 coupon:issue:stock 키로 재고를
#    판정하는데(없으면 STOCK_NOT_INITIALIZED), 그 키를 채우는 곳이 이 API 뿐이다.
#    createCoupon() 이 Redis 를 안 건드리는 게 원인이며 별도 이슈 대상이다.
#
#    응답의 redisStock 은 쓴 값이 아니라 다시 읽은 값이라 검증에 쓸 수 있다.
# ---------------------------------------------------------------------
RES="$(post_json "$BASE_URL/internal/coupons/$COUPON_ID/reset" "{\"totalQuantity\": $TOTAL_QUANTITY}")"
REDIS_STOCK="$(json_value redisStock "$RES")"
[ "$REDIS_STOCK" = "$TOTAL_QUANTITY" ] \
	|| die "Redis 재고 초기화 (redisStock=$REDIS_STOCK, 기대=$TOTAL_QUANTITY)" "$RES"
echo "4/4  Redis 재고 세팅    redisStock=$REDIS_STOCK"

# ---------------------------------------------------------------------
# 확인 — initialized 가 true 여야 실제로 발급이 된다(TC-17).
# false 인데도 remainingQuantity 는 총재고로 나와서 "재고 가득"으로 보이므로
# 이 값을 반드시 같이 본다.
# ---------------------------------------------------------------------
RES="$(curl -s "$BASE_URL/coupons/$COUPON_ID/status")"
INITIALIZED="$(json_value initialized "$RES")"

if [ "$INITIALIZED" != "true" ]; then
	die "initialized=$INITIALIZED — 이 상태로 발급하면 전건 실패한다" "$RES"
fi

cat <<EOF

======================================================
  COUPON_ID = $COUPON_ID       (initialized=true)
======================================================

  쿠폰 상태는 ${OPEN_DELAY_MIN}분 뒤 스케줄러가 READY -> ACTIVE 로 바꾼다.
  발급 자체는 Redis 키만 있으면 되므로 지금도 되지만, 화면 표시를
  맞추려면 그때까지 기다린다.

  k6 실행
    k6 run -e SCENARIO=burst -e COUPON_ID=$COUPON_ID \\
      -e TOTAL_QUANTITY=$TOTAL_QUANTITY -e VUS=$TOTAL_QUANTITY \\
      -e ITERATIONS_PER_VU=1 load-test/k6/issue-coupon.js

  회차 되돌리기 (쿠폰을 새로 만들 필요 없다)
    curl -X POST $BASE_URL/internal/coupons/$COUPON_ID/reset \\
      -H "Content-Type: application/json" -d '{"totalQuantity": $TOTAL_QUANTITY}'

EOF
