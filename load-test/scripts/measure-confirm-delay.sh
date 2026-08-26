#!/bin/bash
#
# 비동기 발급 확정 지연 측정
#
# 부하 종료 직후 실행한다. 1초 간격으로 coupon_issue 건수를 세어
# 목표치에 도달하는 시각을 기록한다.
#
#   ./measure-confirm-delay.sh <couponId> <목표건수> [출력파일]
#
# 예)
#   ./measure-confirm-delay.sh 1 10000
#   ./measure-confirm-delay.sh 1 10000 stage3-run2.csv
#
# 결과는 CSV로 남는다(elapsed_sec, issued_count). 그래프로 확정 곡선을 그릴 수 있다.
# 5분을 넘기면 미확정 건수를 출력하고 종료한다 — 이 값이 곧 유실 의심 건수다.
#
# 폴링 루프는 컨테이너 안에서 돈다. docker exec 를 매 초 호출하면
# 프로세스 기동 비용만 수 초가 들어 1초 간격이 지켜지지 않기 때문이다.
#
set -u

COUPON_ID=${1:-}
TARGET=${2:-}
OUTPUT=${3:-confirm-delay.csv}

MYSQL_CONTAINER=${MYSQL_CONTAINER:-petcoupon-mysql}
MYSQL_USER=${MYSQL_USER:-root}
MYSQL_PASSWORD=${MYSQL_PASSWORD:-root}
MYSQL_DATABASE=${MYSQL_DATABASE:-petcoupon}
TIMEOUT_SEC=${TIMEOUT_SEC:-300}

if [ -z "$COUPON_ID" ] || [ -z "$TARGET" ]; then
  echo "사용법: $0 <couponId> <목표건수> [출력파일]" >&2
  exit 1
fi

echo "elapsed_sec,issued_count" > "$OUTPUT"
echo "확정 지연 측정 시작 — couponId=${COUPON_ID}, 목표=${TARGET}건, 출력=${OUTPUT}"

docker exec -i \
  -e COUPON_ID="$COUPON_ID" \
  -e TARGET="$TARGET" \
  -e TIMEOUT_SEC="$TIMEOUT_SEC" \
  -e MYSQL_PWD="$MYSQL_PASSWORD" \
  -e MYSQL_USER="$MYSQL_USER" \
  -e MYSQL_DATABASE="$MYSQL_DATABASE" \
  "$MYSQL_CONTAINER" bash -s <<'INNER' | tee -a "$OUTPUT"
START=$(date +%s)
while true; do
  COUNT=$(mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" -N \
          -e "SELECT COUNT(*) FROM coupon_issue WHERE coupon_id=${COUPON_ID};" 2>/dev/null)
  COUNT=${COUNT:-0}
  ELAPSED=$(( $(date +%s) - START ))

  echo "${ELAPSED},${COUNT}"

  if [ "$COUNT" -ge "$TARGET" ]; then
    echo "전건 확정 완료: ${ELAPSED}초" >&2
    exit 0
  fi

  if [ "$ELAPSED" -ge "$TIMEOUT_SEC" ]; then
    echo "${TIMEOUT_SEC}초 초과 — 미확정 $(( TARGET - COUNT ))건" >&2
    exit 1
  fi

  sleep 1
done
INNER
