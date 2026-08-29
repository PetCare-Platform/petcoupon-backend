---
title: 장애 · 문제 해결
type: troubleshooting
audience: backend-developer, operator
owner: petcoupon-backend-team
status: maintained
last_verified: 2026-08-28
---

# 장애 · 문제 해결

이 문서는 PetCoupon을 개발·테스트·운영하는 과정에서 발생할 수 있는 문제를 **증상 기준**으로 진단하기 위한 문서입니다.

정상적인 시스템 구조와 설계 이유는 [`architecture.md`](architecture.md), 로컬 실행과 테스트 방법은 [`development.md`](development.md)를 참고합니다.

> **알려진 미비 항목** — Redis Stream DLQ(`coupon:issue:stream:dlq`)에 쌓인 메시지의 운영 절차가 아직 정해지지 않았습니다.
> 자세한 내용은 [3. Stream Consumer가 멈추거나 Pending이 쌓인다](#3-redis-stream-consumer가-멈추거나-pending이-계속-쌓인다)의 마지막 항목을 참고합니다.

---

## 증상으로 찾기

겪고 있는 증상에서 시작합니다.

| 증상 | 항목 |
|---|---|
| `202`는 받았는데 쿠폰이 발급되지 않는다 | [2. 발급이 확정되지 않는다](#2-발급-api는-202인데-쿠폰이-발급되지-않는다) |
| `XPENDING`이 계속 증가한다 · `NOGROUP` 오류 | [3. Stream Consumer가 멈춘다](#3-redis-stream-consumer가-멈추거나-pending이-계속-쌓인다) |
| `issue_message`의 `PENDING`·`FAILED`가 쌓인다 | [4. Kafka 장애 이후 Outbox가 쌓인다](#4-kafka-장애-이후-outbox가-쌓인다) |
| 관리자 DLQ 목록에 메시지가 있다 | [5. DLQ 메시지가 쌓인다](#5-dlq-메시지가-쌓인다) |
| Redis 재고와 DB 재고가 다르다 | [6. 재고가 서로 다르다](#6-redis-재고와-db-재고가-다르다) |
| 재고는 0인데 쿠폰이 `ACTIVE`다 | [7. 상태가 `SOLD_OUT`으로 안 바뀐다](#7-redis-재고는-0인데-쿠폰-상태가-active다) |
| 초기화 API가 `409`를 반환한다 | [8. 초기화가 거절된다](#8-초기화-api가-409를-반환한다) |
| 정합성 검증이 실행되지 않는다 | [9. 정합성 검증이 거절된다](#9-정합성-검증이-실행되지-않는다) |
| 테스트가 단독은 성공, 전체는 실패한다 | [10. 테스트가 간헐적으로 실패한다](#10-테스트가-단독-실행하면-성공하지만-전체-실행하면-실패한다) |
| k6 성공률은 높은데 발급 건수가 이상하다 | [11. 부하 테스트 결과가 안 맞는다](#11-부하-테스트의-성공률은-높은데-실제-발급-건수가-이상하다) |
| 관리자 API가 `401`을 반환한다 | [12. 관리자 인증 실패](#12-관리자-api가-401을-반환한다) |
| 모니터링 SSE가 연결되지 않는다 | [13. SSE 연결 문제](#13-모니터링-sse가-연결되지-않거나-계속-재접속한다) |
| 없는 URL이 `500`을 반환한다 | [14. 잘못된 URL이 500으로 처리된다](#14-잘못된-url이-500으로-처리된다) |
| 발급 상태가 계속 `WAITING`이다 | [15. `WAITING` 고정](#15-발급-상태가-계속-waiting에-고정된다) |

어느 항목인지 모르겠다면 [1. 먼저 확인할 것](#1-먼저-확인할-것)부터 순서대로 확인합니다.

---

## 진단 원칙

쿠폰 발급은 여러 단계를 거치는 비동기 파이프라인이라, **어느 단계에서 멈췄는지**를 먼저 좁혀야 합니다.

```text
증상 확인
   ↓
어느 단계에서 멈췄는지 확인
   ↓
Redis Stream → Outbox → Kafka → DB 발급 확정
   ↓
복구
   ↓
동일한 방법으로 다시 검증
```

순서는 항상 다음을 유지합니다.

> **진단 → 기존 자동 복구 확인 → 관리자 복구 기능 사용 → 최종 검증**

장애 분석 중 DB나 Redis 값을 임의로 먼저 수정하면 원래 실패 원인을 잃어버리고 정합성을 더 깨뜨릴 수 있습니다.

---

## 1. 먼저 확인할 것

쿠폰 발급 문제를 확인하기 전에 애플리케이션과 의존 인프라 상태부터 확인합니다.

### 컨테이너 상태

```bash
docker compose ps
```

쿠폰 발급 파이프라인을 사용하려면 MySQL, Redis뿐 아니라 Kafka도 실행되어 있어야 합니다.

```bash
docker compose --profile kafka up -d
```

Kafka는 Compose profile로 분리되어 있으므로 `docker compose up -d`만 실행하면 Kafka가 올라오지 않습니다.

### 포트 충돌

컨테이너가 뜨지 않으면 포트가 이미 점유돼 있는지 확인합니다. 필요한 포트는 `3306`(MySQL), `6379`(Redis), `9092`(Kafka), `8080`(애플리케이션)입니다.

```bash
docker compose --profile kafka logs mysql | tail -20
```

`3306` 충돌은 Windows에 MySQL이 서비스로 설치돼 있는 경우가 대부분입니다. **관리자 권한 PowerShell**에서 중지합니다.

```powershell
net stop MySQL80
```

### 애플리케이션 상태

```bash
curl -s http://localhost:8080/actuator/health
```

### Redis 확인

```bash
docker exec petcoupon-redis redis-cli PING
```

정상이면 `PONG`이 반환됩니다.

### Kafka 확인

```bash
docker exec petcoupon-kafka \
  /opt/kafka/bin/kafka-broker-api-versions.sh \
  --bootstrap-server localhost:9092
```

`/actuator/health` 또는 `/admin/system/health`가 정상이라고 해서 쿠폰 발급 파이프라인 전체가 정상이라고 단정하지 않습니다. 쿠폰 발급이 멈춘 경우 Kafka와 Consumer 상태도 별도로 확인합니다.

---

## 2. 발급 API는 202인데 쿠폰이 발급되지 않는다

### 증상

```text
POST /coupons/{couponId}/issues
→ 202 Accepted
→ status = WAITING
```

까지 정상인데 이후 발급 결과가 확정되지 않습니다. 사용자의 발급 상태 조회에서도 계속 `WAITING`이 유지되거나, `coupon_issue` 데이터가 증가하지 않습니다.

### 먼저 알아야 할 점

PetCoupon의 발급 API는 비동기입니다. `202 Accepted`는 **발급 요청을 접수했다**는 의미이지, **쿠폰 발급이 완료됐다**는 의미가 아닙니다.

정상적인 발급 흐름은 다음과 같습니다.

```text
HTTP 202
   ↓
Redis Stream
   ↓
Stream Consumer
   ↓
Lua Script
   ↓
issue_message(Outbox)
   ↓
Kafka
   ↓
Kafka Consumer
   ↓
coupon_issue / coupon_stock / history
   ↓
최종 상태 확정
```

따라서 **어느 단계에서 멈췄는지 순서대로 확인**해야 합니다.

### 진단 1 — Redis Stream

```bash
docker exec petcoupon-redis \
  redis-cli XINFO GROUPS coupon:issue:stream
```

Consumer Group `coupon-issue-group`을 확인합니다. Pending도 확인합니다.

```bash
docker exec petcoupon-redis \
  redis-cli XPENDING coupon:issue:stream coupon-issue-group
```

| 관찰 | 다음 단계 |
|---|---|
| Stream에 메시지가 계속 쌓임 | Consumer 확인 → [3. Stream Consumer](#3-redis-stream-consumer가-멈추거나-pending이-계속-쌓인다) |
| Pending이 계속 증가함 | Consumer 처리 실패 또는 후속 처리 실패 → [3. Stream Consumer](#3-redis-stream-consumer가-멈추거나-pending이-계속-쌓인다) |
| Stream은 정상 처리됨 | 진단 2로 이동 |

### 진단 2 — Outbox

```sql
SELECT status, COUNT(*)
FROM issue_message
GROUP BY status
ORDER BY status;
```

주요 상태의 의미는 다음과 같습니다.

| 상태 | 의미 |
|---|---|
| `PENDING` | Kafka 발행 대기 |
| `FAILED` | Kafka 발행 실패, 자동 재시도 대상 |
| `SENT` | Kafka 발행 완료, DB 최종 처리는 아직 완료되지 않음 |
| `CONSUMED` | 최종 소비 완료 |
| `DLQ` | 자동 재시도 소진, 관리자 판단 필요 |
| `ABANDONED` | 관리자가 발급을 포기한 메시지 |

최근 실패 원인은 다음 쿼리로 확인할 수 있습니다.

```sql
SELECT
    message_id,
    coupon_id,
    message_key,
    status,
    retry_count,
    last_error,
    created_at,
    processed_at,
    stock_restored_at
FROM issue_message
WHERE status IN ('PENDING', 'FAILED', 'SENT', 'DLQ', 'ABANDONED')
ORDER BY message_id DESC
LIMIT 50;
```

| 관찰 | 다음 단계 |
|---|---|
| `PENDING` · `FAILED` 증가 | Kafka 발행 단계 → 진단 3, [4. Outbox 적체](#4-kafka-장애-이후-outbox가-쌓인다) |
| `SENT`가 계속 남음 | Kafka 발행 이후 Consumer 또는 DB 반영 단계 → 진단 3 |
| `DLQ` 존재 | [5. DLQ 처리](#5-dlq-메시지가-쌓인다) |

### 진단 3 — Kafka

```bash
docker compose ps kafka
docker logs --tail=100 petcoupon-kafka
```

Consumer Group과 Lag을 확인합니다.

```bash
docker exec petcoupon-kafka \
  /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group petcoupon \
  --describe
```

Consumer Lag이 계속 증가하고 줄지 않는다면 Kafka Consumer 또는 애플리케이션 로그를 확인합니다.

### 해결

Kafka 또는 Redis가 일시적으로 중단된 경우 먼저 인프라를 정상화합니다.

```bash
docker compose --profile kafka up -d
```

이후 **즉시 DB 데이터를 수동으로 수정하지 않습니다.** PetCoupon에는 Redis Pending Recovery와 Outbox 재시도가 있으므로 먼저 자동 복구가 진행되는지 확인합니다.

### 해결 확인

다음 순서로 진행되는지 확인합니다.

```text
Redis Stream Pending 감소
        ↓
issue_message PENDING/FAILED 감소
        ↓
SENT → CONSUMED
        ↓
coupon_issue 생성
        ↓
사용자 발급 상태 최종 확정
```

```sql
SELECT status, COUNT(*) FROM issue_message GROUP BY status;
```

### 그래도 실패하면

자동 복구 주기(Outbox 1초 × 최대 5회, Stream Recovery 5초 × 최대 3회)를 충분히 넘겼는데도 상태가 변하지 않으면 [에스컬레이션](#에스컬레이션--수집할-정보)의 정보를 수집합니다.

관련 이슈/PR: #47, #49, #72, #78, #84, #93, #153, #161, #162, #168, #169

---

## 3. Redis Stream Consumer가 멈추거나 Pending이 계속 쌓인다

### 증상

- 발급 요청은 계속 접수됨
- `XPENDING`이 계속 증가함
- Stream 메시지가 처리되지 않음
- Redis 재시작 후 `NOGROUP` 오류 발생
- Consumer 오류 이후 처리가 다시 시작되지 않는 것처럼 보임

### 현재 복구 동작

현재 구현은 Stream Consumer 오류가 발생했다고 Consumer를 영구 종료하지 않습니다. 연결 또는 읽기 오류가 발생하면 다음 간격으로 재시도합니다.

```text
1초 → 2초 → 4초 → 8초 → ... → 최대 30초
```

재시도 횟수 제한은 두지 않습니다.

Consumer Group이 사라져 `NOGROUP`이 발생한 경우 애플리케이션이 Group을 다시 생성하며, 기존 Stream 메시지를 다시 처리할 수 있도록 `0-0`부터 복구합니다.

따라서 **`NOGROUP` 발생 직후 수동으로 Redis 데이터를 삭제하거나 Consumer Group을 임의로 다시 만드는 것은 피합니다.**

### Pending Recovery

처리 도중 실패한 메시지는 ACK하지 않습니다.

```text
처리 성공 → XACK
처리 실패 → ACK하지 않음 → Pending 유지
```

기본 설정은 다음과 같습니다.

| 항목 | 기본값 |
|---|---|
| 최소 idle 시간 | 1분 |
| 복구 확인 주기 | 5초 |
| 최대 처리 횟수 | 3회 |
| 복구 batch | 100건 |

여기서 최대 처리 횟수 `3회`는 최초 처리도 포함합니다.

```text
최초 처리
   ↓ 실패
Recovery 1
   ↓ 실패
Recovery 2
   ↓ 실패
Redis Stream DLQ
```

### 진단

```bash
# Consumer Group
docker exec petcoupon-redis \
  redis-cli XINFO GROUPS coupon:issue:stream

# Pending
docker exec petcoupon-redis \
  redis-cli XPENDING coupon:issue:stream coupon-issue-group

# Redis Stream DLQ
docker exec petcoupon-redis \
  redis-cli XLEN coupon:issue:stream:dlq
```

애플리케이션 로그에서는 다음 키워드를 확인합니다.

```text
Redis Stream · Pending Recovery · NOGROUP · XCLAIM · INCONSISTENT_STATE · Outbox
```

### 중요한 구분 — 두 개의 DLQ

| DLQ | 위치 | 의미 |
|---|---|---|
| `coupon:issue:stream:dlq` | Redis | Redis Stream 처리 단계에서 실패한 메시지 |
| `issue_message.status = DLQ` | MySQL | Outbox/Kafka 발행 단계에서 자동 재시도가 소진된 메시지 |

관리자 API `GET /admin/coupon-issue/dlq`가 조회하는 것은 **DB의 `issue_message` DLQ**입니다. Redis Stream DLQ와 혼동하지 않습니다.

### 해결 확인

```bash
docker exec petcoupon-redis \
  redis-cli XPENDING coupon:issue:stream coupon-issue-group
```

Pending이 감소하는지 확인하고, DB의 Outbox 상태도 같이 확인합니다.

```sql
SELECT status, COUNT(*) FROM issue_message GROUP BY status;
```

### 그래도 실패하면

`XLEN coupon:issue:stream:dlq`가 증가하고 있다면 최대 처리 횟수(3회)를 소진한 메시지입니다. 애플리케이션 로그에서 실패 원인을 먼저 확인합니다.

> **⚠️ 수정 필요 — Redis Stream DLQ 운영 절차 미정**
>
> `coupon:issue:stream:dlq`에 쌓인 메시지를 조회·재처리하는 **관리자 API가 현재 없습니다.**
> 관리자 DLQ API(`/admin/coupon-issue/dlq`)는 DB의 `issue_message`만 다룹니다.
>
> 이 메시지들을 어떻게 처리할지(수동 `XRANGE` 조회 후 재적재 / 재처리 API 신설 / 폐기 허용)
> 아직 팀에서 정해지지 않았습니다. 절차가 확정되면 이 항목을 채워야 합니다.

관련 이슈/PR: #47, #49, #72, #78, #121, #128, #129, #134, #153, #169

---

## 4. Kafka 장애 이후 Outbox가 쌓인다

### 증상

- `issue_message.PENDING` 증가
- `issue_message.FAILED` 증가
- 발급 요청은 `202`로 정상 접수됨
- 실제 발급 확정 수는 증가하지 않음
- Kafka Broker 장애 로그 발생

### 원인

발급 접수와 Kafka 발행은 분리되어 있습니다. 따라서 Kafka가 중단되어 있어도 Redis Stream까지 요청이 접수되면 API는 `202`를 반환할 수 있습니다.

**Kafka 장애 여부를 HTTP 202만 보고 판단할 수 없습니다.**

### 현재 Outbox 정책

| 항목 | 기본값 |
|---|---|
| Outbox polling | 1초 |
| batch size | 100 |
| 최대 retry count | 5 |
| Kafka `max.block.ms` | 10초 |
| Kafka `request.timeout.ms` | 5초 |
| Kafka `delivery.timeout.ms` | 10초 |

Kafka 전송 실패 시 `last_error`에 실제 root cause를 최대한 보존합니다. 따라서 장애 분석 시 단순히 상태만 보지 말고 `last_error`를 같이 확인합니다.

```sql
SELECT
    message_id,
    status,
    retry_count,
    last_error
FROM issue_message
WHERE status IN ('FAILED', 'DLQ')
ORDER BY message_id DESC;
```

### 해결

먼저 Kafka를 복구합니다.

```bash
docker compose --profile kafka up -d
```

Broker 동작을 확인합니다.

```bash
docker exec petcoupon-kafka \
  /opt/kafka/bin/kafka-broker-api-versions.sh \
  --bootstrap-server localhost:9092
```

이후 `PENDING`, `FAILED` 메시지가 자동으로 재발행되는지 확인합니다. **재시도 소진 전이라면 관리자가 개별 메시지를 직접 건드릴 필요가 없습니다.**

### 해결 확인

```sql
SELECT status, COUNT(*) FROM issue_message GROUP BY status;
```

정상적으로 복구되면 `PENDING`·`FAILED` 감소 → `SENT` → `CONSUMED`로 진행합니다.

### 그래도 실패하면

`retry_count`가 5에 도달한 메시지는 `DLQ`로 이동합니다. [5. DLQ 메시지가 쌓인다](#5-dlq-메시지가-쌓인다)로 이동합니다.

관련 이슈/PR: #112, #118, #161, #162, #168

---

## 5. DLQ 메시지가 쌓인다

### 증상

관리자 DLQ 목록(`GET /admin/coupon-issue/dlq`)에 메시지가 존재하거나, DB에서 `issue_message.status = DLQ`가 확인됩니다.

### 먼저 확인할 것

**DLQ에 들어갔다고 무조건 재처리하지 않습니다.** 먼저 `last_error`를 확인해 실패 원인이 일시적인 장애인지 판단합니다.

| 원인 | 판단 |
|---|---|
| Kafka 일시 장애 · 연결 실패 · Broker 재시작 · 일시적 네트워크 문제 | 재처리 후보 |
| 데이터 자체가 잘못되어 반복 실패 | 원인을 수정하기 전 재처리를 반복하지 않음 |

### 재처리

```text
POST /admin/coupon-issue/dlq/{messageId}/reprocess
```

`reprocess`는 Kafka 재발행을 **트리거**합니다. **API가 성공했다고 최종 쿠폰 발급까지 끝난 것은 아닙니다.** 이후 메시지 상태를 다시 확인합니다.

### 발급 포기

해당 메시지를 더 이상 발급하면 안 된다고 판단한 경우 `abandon`을 사용합니다.

```text
POST /admin/coupon-issue/dlq/{messageId}/abandon
```

`abandon`은 단순히 DLQ에서 제거하는 작업이 아닙니다.

```text
DLQ
 ↓
ABANDONED 선점
 ↓
Redis 예약 재고 복구
 ↓
stock_restored_at 기록
```

### abandon 중 503이 발생한 경우

현재 구조에서는 먼저 DB 상태가 `ABANDONED`로 커밋되고 그 후 Redis 재고 복구가 수행됩니다. 따라서 Redis 장애 등으로 복구가 실패하면 다음 상태가 가능합니다.

```text
status = ABANDONED
stock_restored_at = NULL
```

확인 쿼리는 다음과 같습니다.

```sql
SELECT
    message_id,
    coupon_id,
    message_key,
    status,
    stock_restored_at
FROM issue_message
WHERE status = 'ABANDONED'
  AND stock_restored_at IS NULL;
```

이 상태에서는 **같은 `messageId`로 abandon을 다시 호출**할 수 있습니다. Redis Lua 복구 로직은 이미 복구된 요청에 대해 `ALREADY_RESTORED`를 반환하도록 구현되어 있어 안전합니다.

**직접 Redis 재고를 `INCR`하는 식으로 복구하지 않습니다.**

### 해결 확인

```sql
SELECT
    message_id,
    status,
    stock_restored_at
FROM issue_message
WHERE message_id = ?;
```

발급 포기를 완료한 메시지는 `status = ABANDONED`이면서 `stock_restored_at IS NOT NULL`인지 확인합니다.

재처리한 메시지는 `CONSUMED`로 진행하는지, 그리고 `coupon_issue`에 실제 발급이 생성됐는지 확인합니다.

### 그래도 실패하면

같은 메시지가 재처리 후 다시 `DLQ`로 돌아온다면 일시적 장애가 아닙니다. `last_error`를 근거로 원인을 수정하기 전까지 재처리를 반복하지 않습니다.

관련 이슈/PR: #75, #100, #141, #149, #150

---

## 6. Redis 재고와 DB 재고가 다르다

### 증상

Redis에서 보는 실시간 재고와 `coupon_stock.remaining_quantity`가 일치하지 않습니다.

### 반드시 알아야 할 점

**이 둘은 동일한 의미의 값이 아닙니다.**

| 구분 | 의미 |
|---|---|
| Redis 재고 | 요청 처리 시점의 선점 가능한 재고 |
| DB 재고 | Kafka Consumer를 거쳐 최종 확정된 발급 재고 |

즉 발급이 처리 중이면 잠시 다음 상태가 가능하며, 이는 즉시 정합성 오류라고 판단할 수 없습니다.

```text
Redis remaining = 0
DB remaining    = 15
```

### 진단

```sql
SELECT
    coupon_id,
    total_quantity,
    remaining_quantity
FROM coupon_stock
WHERE coupon_id = ?;
```

그리고 다음을 같이 확인합니다.

```sql
SELECT status, COUNT(*)
FROM issue_message
WHERE coupon_id = ?
GROUP BY status;
```

`PENDING`, `FAILED`, `SENT`가 존재한다면 아직 비동기 처리가 끝나지 않았을 수 있습니다. Redis Stream Pending도 확인합니다.

```bash
docker exec petcoupon-redis \
  redis-cli XPENDING coupon:issue:stream coupon-issue-group
```

### 비정상으로 판단할 시점

다음 조건이 **모두** 만족하는데 재고가 계속 다르면 정합성 문제를 의심합니다.

```text
Stream 미배달 없음
Pending 없음
Outbox PENDING 없음
Outbox FAILED 없음
Outbox SENT 없음
Kafka Consumer 처리 완료
```

### 해결 확인

`ENDED` 쿠폰이라면 정합성 검증을 실행할 수 있습니다.

```text
POST /admin/coupons/{couponId}/reconcile
```

검증이 거절된다면 [9. 정합성 검증이 실행되지 않는다](#9-정합성-검증이-실행되지-않는다)를 확인합니다.

관련 이슈/PR: #114, #120, #130, #133, #144, #145, #149, #150

---

## 7. Redis 재고는 0인데 쿠폰 상태가 ACTIVE다

### 증상

실시간 발급에서는 재고가 모두 소진된 것처럼 보이지만 `coupon.status = ACTIVE` 상태가 잠시 유지됩니다.

### 원인

**`SOLD_OUT` 판정 기준은 Redis 재고가 아닙니다.** Coupon Status Scheduler는 DB의 `coupon_stock.remaining_quantity = 0`을 기준으로 `ACTIVE → SOLD_OUT`을 수행하며, 기본 주기는 60초입니다.

따라서 다음 시간차는 정상적으로 발생할 수 있습니다.

```text
Redis 재고 0
   ↓
Kafka Consumer가 DB 발급 확정
   ↓
DB remaining_quantity 0
   ↓
Coupon Status Scheduler (최대 60초)
   ↓
SOLD_OUT
```

### 비정상으로 볼 수 있는 경우

DB에서도 이미 `remaining_quantity = 0`이고 60초 이상 지났는데 계속 `ACTIVE`라면 Scheduler를 확인합니다.

| 구분 | 값 |
|---|---|
| 환경변수 | `COUPON_STATUS_SCHEDULER_ENABLED` |
| 프로퍼티 | `coupon.status.enabled` |
| 기본값 | `true` |

### 로그 확인

```text
쿠폰 상태 전이 완료
쿠폰 상태 전이 스케줄러 실행 중 오류
```

Scheduler 내부 오류가 한 번 발생했다고 이후 실행이 영구 중단되지는 않습니다. 현재 구현에서는 외부에서 예외를 잡고 다음 주기에 재시도합니다.

### 해결 확인

다음 주기(최대 60초) 이후 상태가 전이되는지 확인합니다.

```text
GET /admin/coupons/{couponId}/status
```

관련 이슈/PR: #73, #74, #144, #145

---

## 8. 초기화 API가 409를 반환한다

### 증상

`POST /internal/coupons/{couponId}/reset` 호출 시 초기화가 거절됩니다.

```text
앞 회차 메시지가 아직 처리 중이라 초기화할 수 없습니다.
```

### 원인

초기화는 단순히 DB 데이터를 삭제하는 API가 아닙니다. 이전 회차 메시지가 남아 있는 상태에서 DB와 Redis만 초기화하면 이전 메시지가 뒤늦게 처리되어 새 회차 재고를 차감하는 **유령 발급**이 발생할 수 있습니다.

그래서 초기화 전에 Pipeline Drain 상태를 검사합니다.

```text
Redis Stream 미배달
Redis Stream Pending
Outbox PENDING
Outbox FAILED
Outbox SENT
```

**Redis 검사 자체가 실패한 경우에도 "남은 메시지가 없다"고 판단하지 않고 안전하게 초기화를 막습니다.**

### 진단 1 — Redis

```bash
docker exec petcoupon-redis \
  redis-cli XINFO GROUPS coupon:issue:stream

docker exec petcoupon-redis \
  redis-cli XPENDING coupon:issue:stream coupon-issue-group
```

### 진단 2 — Outbox

```sql
SELECT status, COUNT(*)
FROM issue_message
WHERE coupon_id = ?
GROUP BY status;
```

`PENDING`, `FAILED`, `SENT`가 없어야 합니다.

### 진단 3 — Kafka Lag

Pipeline Drain Checker가 Kafka Broker 내부의 모든 상태를 직접 확인하는 것은 아닙니다. 초기화 전에 Consumer Lag도 함께 확인하는 것이 안전합니다.

```bash
docker exec petcoupon-kafka \
  /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group petcoupon \
  --describe
```

### 해결

우선 이전 회차 메시지가 정상적으로 끝날 때까지 기다리고 자동 복구가 동작하는지 확인합니다. 그 후 다시 초기화합니다.

```bash
curl -X POST http://localhost:8080/internal/coupons/1/reset \
  -H "Content-Type: application/json" \
  -d '{"totalQuantity":10000}'
```

### `force=true` 사용

초기화 요청에는 `force` 옵션이 있지만 **정상적인 반복 테스트에서 사용하지 않습니다.**

```json
{
  "totalQuantity": 10000,
  "force": true
}
```

`force=true`는 복구할 수 없는 Pending 등 사람이 확인한 특수 상황에서만 사용합니다. **강제 초기화를 실행한 회차의 테스트 결과는 신뢰하지 않는 것이 원칙입니다.**

또한 Pipeline Drain 검사와 실제 Reset 사이에는 하나의 원자적 락이 존재하지 않으므로, 초기화 직전까지 발급 트래픽이 들어오는 환경에서 `force`를 안전장치처럼 사용해서는 안 됩니다.

### 해결 확인

초기화 응답의 `redisStock`과 요청한 `totalQuantity`를 비교합니다.

```text
redisStock == totalQuantity
```

이어 DB도 확인합니다.

```sql
SELECT
    total_quantity,
    remaining_quantity
FROM coupon_stock
WHERE coupon_id = ?;
```

여러 대의 k6를 사용하는 경우 초기화를 부하 실행과 동시에 수행하지 않습니다. 초기화를 별도 단계로 끝낸 후 모든 부하 발생기를 `RESET=false`로 시작합니다.

관련 이슈/PR: #38, #88, #92, #139, #140, #160, #164

---

## 9. 정합성 검증이 실행되지 않는다

### 증상

수동 정합성 검증(`POST /admin/coupons/{couponId}/reconcile`)이 거절되거나 자동 Reconciliation이 실행되지 않습니다.

### 사전 조건 1 — Coupon은 ENDED 상태여야 한다

정합성 검증은 최종 결과를 판정하는 Batch이기 때문에 발급 중인 쿠폰에는 실행하지 않습니다.

```text
CouponStatus != ENDED → 검증 거절
```

### 사전 조건 2 — 발급 파이프라인이 비어 있어야 한다

다음 상태가 남아 있으면 검증하지 않습니다.

```text
Stream 미배달
Stream Pending
Outbox PENDING
Outbox FAILED
Outbox SENT
```

처리 중인 메시지가 남아 있는 상태에서 정합성을 검사하면 **정상적인 eventual consistency를 오류로 판단**할 수 있기 때문입니다.

### 자동 Reconciliation

| 항목 | 값 |
|---|---|
| 기본 주기 | 30분 |
| 대상 | `ENDED` Coupon |

한 쿠폰의 검증이 실패해도 다른 쿠폰의 검증까지 중단하지 않습니다. 실패한 쿠폰은 다음 주기에 다시 시도됩니다.

### 부하 테스트 중에는

대량 데이터 환경에서 Reconciliation이 측정 구간에 같이 실행되면 DB 부하가 섞일 수 있습니다. 부하 테스트에서는 필요하면 다음 환경변수로 끕니다.

```text
COUPON_RECONCILIATION_SCHEDULER_ENABLED=false
```

### 해결 확인

정합성 검증 이력을 확인합니다.

```text
GET /admin/coupons/{couponId}/reconciliation-reports
```

관련 이슈/PR: #111, #133, #149, #150, #154, #155

---

## 10. 테스트가 단독 실행하면 성공하지만 전체 실행하면 실패한다

### 증상

- 테스트 하나만 실행하면 성공
- 전체 테스트 실행 시 간헐적으로 실패
- 예상하지 않은 Event/Coupon 상태 변경
- `event_status_history` 등의 FK 때문에 삭제 실패
- 테스트 도중 데이터가 갑자기 변경됨

### 원인

`@SpringBootTest`로 애플리케이션 Context를 띄우면 **실제 Scheduler도 같이 실행될 수 있습니다.**

예를 들어 Event Scheduler는 기본적으로 매분 실행되며 `event_status_history`를 변경할 수 있습니다. Coupon Status Scheduler 역시 테스트 도중 `READY → ACTIVE` 등의 상태 전이를 수행할 수 있습니다. 이는 테스트 스레드와 별개의 백그라운드 작업입니다.

### 해결

해당 테스트에서 Scheduler 자체를 검증하는 것이 아니라면 필요한 Scheduler를 끕니다.

```java
@SpringBootTest(properties = {
    "event.status.scheduler.enabled=false",
    "coupon.status.enabled=false",
    "coupon.reconciliation.scheduler.enabled=false"
})
```

모든 테스트에서 무조건 세 개를 끌 필요는 없습니다.

> 테스트 대상이 아닌 백그라운드 작업만 비활성화합니다.

Scheduler 동작 자체를 검증하는 테스트에서는 해당 Scheduler를 활성화해야 합니다.

### Context 재사용 주의

Spring Test Context Cache 때문에 한 테스트의 설정이나 백그라운드 작업이 다른 테스트와 간섭하는지 확인합니다.

Scheduler 설정을 적극적으로 변경하는 테스트에서 Context 생명주기 자체가 문제라면 `@DirtiesContext` 적용 여부를 검토합니다. 다만 **무조건 붙이는 것은 권장하지 않습니다.** Context 재생성 비용이 큽니다.

### 해결 확인

개별 테스트가 아니라 전체 Suite로 다시 실행합니다.

```bash
./gradlew test
```

관련 이슈/PR: #80, #82, #89, #94, #99, #101, #154, #155

---

## 11. 부하 테스트의 성공률은 높은데 실제 발급 건수가 이상하다

### 증상

k6에서는 `http_req_failed ≈ 0`이고 `202` 응답이 대부분 성공인데 DB 발급 수량이 기대값과 다릅니다.

### 원인 1 — 202를 발급 성공으로 해석함

k6가 측정하는 것은 기본적으로 **접수 성능**입니다. `202`는 접수 성공이지 쿠폰 발급 성공이 아닙니다.

최종 결과는 DB 검증 스크립트로 확인합니다.

```text
load-test/sql/verify_issue_result.sql
```

### 원인 2 — Kafka가 실행되지 않음

가장 먼저 확인합니다.

```bash
docker compose --profile kafka up -d
```

Kafka profile을 빼면 API는 정상 접수되지만 발급 확정이 진행되지 않을 수 있습니다.

### 원인 3 — 잘못된 회원 ID

대량 삽입된 `app_user.user_id`는 **반드시 연속이라고 가정할 수 없습니다.** `1, 2, 3, 4 ...`를 임의로 만들어 요청하지 않고 실제 DB에서 회원 ID를 추출해 `members.csv`를 생성합니다.

### 원인 4 — 멱등키 재사용

일반 부하 테스트에서는 사용자와 Idempotency-Key를 요청마다 구분합니다. `RUN_ID`, `INSTANCE_INDEX` 등이 이전 회차와 겹치지 않는지 확인합니다.

다음 옵션은 중복 처리 검증용이므로 일반 부하 측정에 사용하지 않습니다.

```text
FIXED_USER_ID
FIXED_IDEMPOTENCY_KEY
```

### 원인 5 — DB Connection Pool 포화

높은 동시성에서 다음 현상이 같이 나타나면 DB Pool을 확인합니다.

- 5xx 증가
- 응답 시간이 수십 초 단위로 증가
- Connection 획득 timeout
- Tomcat Worker는 많은데 DB 작업이 진행되지 않음

`DB_POOL_SIZE`와 `TOMCAT_MAX_THREADS`는 서로 독립적인 숫자로 무작정 높이지 않습니다. Tomcat Worker만 크게 늘리고 DB Pool이 작으면 DB Connection 대기 요청만 증가할 수 있습니다. 부하 테스트에서는 **두 값을 같이 조정하면서** 측정합니다.

### 원인 6 — Scheduler가 측정 구간에 개입함

특히 Reconciliation은 발급 이력이 많은 `ENDED` Coupon을 순회하므로 성능 측정에 DB 부하를 추가할 수 있습니다. 순수 발급 성능 측정 시 다음을 확인합니다.

```text
COUPON_RECONCILIATION_SCHEDULER_ENABLED=false
```

### 해결 확인

k6 결과만 보지 않고 반드시 다음을 같이 확인합니다.

```text
HTTP 결과
+ verify_issue_result.sql
+ issue_message 상태
+ coupon_issue 건수
+ coupon_stock
```

자세한 실행 절차는 [`../load-test/README.md`](../load-test/README.md)를 참고합니다.

관련 이슈/PR: #105, #123, #138, #148, #175, #176

---

## 12. 관리자 API가 401을 반환한다

### 증상

`/admin/**` 호출 시 `401 Unauthorized`가 반환됩니다.

### 원인

관리자 API는 `X-ADMIN-KEY` 헤더의 세션 토큰을 확인합니다. 다음 경우 모두 유효하지 않은 세션으로 처리합니다.

- 헤더 없음
- 잘못된 토큰
- 만료된 토큰

**보안을 위해 서버는 이 세 경우를 클라이언트에게 구분해서 알려주지 않습니다.**

### 진단

먼저 관리자 세션을 다시 발급합니다.

```bash
curl -s -X POST http://localhost:8080/admin/auth/sessions \
  -H "Content-Type: application/json" \
  -d '{"authCode":"local-dev-admin-auth-code"}'
```

그리고 이후 요청에 `X-ADMIN-KEY: {token}`을 전달합니다.

세션은 Redis에 저장되므로 Redis 상태도 확인합니다.

```bash
docker exec petcoupon-redis redis-cli PING
```

기본 세션 TTL은 **8시간**입니다. 배포 환경에서는 개발용 기본 인증 코드를 사용하지 말고 `ADMIN_AUTH_CODE`를 별도로 설정합니다.

### 해결 확인

새로운 세션으로 관리자 API를 다시 호출합니다.

```bash
curl -s http://localhost:8080/admin/events/1 -H "X-ADMIN-KEY: {token}"
```

관련 PR: #108

---

## 13. 모니터링 SSE가 연결되지 않거나 계속 재접속한다

### 증상

`GET /admin/monitoring/stream`에서 다음이 발생합니다.

- 401 발생
- 계속 재연결
- 연결은 됐지만 이벤트가 보이지 않음
- 끊겼다가 연결한 뒤 과거 이벤트가 보이지 않음

### 브라우저 EventSource를 사용하고 있는지 확인

이 엔드포인트는 `X-ADMIN-KEY`를 요구하는데, **브라우저 네이티브 `EventSource`는 임의의 HTTP Header를 추가할 수 없습니다.**

따라서 `@microsoft/fetch-event-source` 같은 fetch 기반 SSE Client를 사용합니다.

### 401 재연결

세션이 만료된 경우 401은 재시도 가능한 네트워크 장애로 취급하지 않습니다. 클라이언트가 무한 재연결하면 만료된 세션으로 Redis 검증 요청만 반복하게 됩니다.

```text
401
→ SSE retry 중단
→ 관리자 재로그인
→ 새로운 세션으로 연결
```

### 연결이 끊긴 동안의 이벤트가 없다

**정상 동작입니다.** 현재 Monitoring SSE는 이벤트 ID와 서버 측 replay buffer를 사용하지 않습니다.

과거 집계가 필요한 값은 Dashboard/Statistics API에서 확인합니다.

### 일부 이벤트가 누락된다

느린 Client의 Queue가 가득 차면 비즈니스 요청을 블로킹하지 않고 Monitoring Event를 버리는 구조입니다.

| 구분 | 확인 방법 |
|---|---|
| 해당 연결의 유실 | `events-dropped` 이벤트로 유실 건수 통지 |
| 전체 유실량 | `monitoring.sse.events.dropped` Metric |

### 연결 수 초과

기본 최대 연결 수는 **50**입니다. 초과하면 새 SSE 연결이 거절될 수 있습니다. `MONITORING_SSE_MAX_SUBSCRIPTIONS` 설정을 확인합니다.

### 설정 OFF 후 재시작

Monitoring Stream의 ON/OFF 상태는 DB나 Redis에 영속화하지 않습니다. 애플리케이션 재기동 후 기본값 **ON**으로 시작합니다.

### 해결 확인

```bash
curl -s http://localhost:8080/admin/monitoring/settings -H "X-ADMIN-KEY: {token}"
```

`streamEnabled`가 `true`인지 확인한 뒤 다시 연결합니다.

관련 PR: #179

---

## 14. 잘못된 URL이 500으로 처리된다

과거 존재했던 회귀 확인용 항목입니다.

존재하지 않는 API(`GET /not-existing-path`)는 서버 내부 오류가 아니므로 정상적으로는 `404`를 반환해야 합니다.

Catch-all Exception Handler 변경 이후 존재하지 않는 URL이 다시 `500`으로 변했다면 `NoResourceFoundException` 처리 경로를 확인합니다.

### 해결 확인

```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/not-existing-path
# expected: 404
```

관련 이슈/PR: #157, #159

---

## 15. 발급 상태가 계속 WAITING에 고정된다

단순히 `WAITING`이 잠시 유지되는 것은 비동기 처리상 정상입니다.

다만 다음 조건을 **모두** 만족했는데 계속 `WAITING`이면 정상적인 지연으로 보기 어렵습니다.

```text
Stream 처리 완료
Pending 없음
Outbox 처리 완료
Kafka Consumer 처리 완료
DB 최종 상태 존재
```

이 경우 Idempotency 최종 상태 갱신을 확인합니다.

과거 비동기 Consumer가 최종 상태를 변경한 뒤 최초 HTTP 요청 Transaction이 늦게 끝나면서 다시 `WAITING`으로 덮어쓰는 **Race Condition**이 존재했고 수정된 이력이 있습니다.

따라서 동일 현상이 재발하면 단순 Kafka 지연이 아니라 **회귀 가능성**도 확인합니다.

### 해결 확인

```text
GET /users/{userId}/coupon-issue-requests/status?idempotencyKey={key}
```

최종 상태가 반환되는지 확인합니다.

관련 이슈/PR: #84, #93, #136

---

## Sharp Edges

운영 및 테스트 시 특히 오해하기 쉬운 부분입니다.

| 항목 | 주의사항 |
|---|---|
| `202 Accepted` | 발급 완료가 아니라 접수 완료 |
| Redis 재고 | 실시간 선점 상태 |
| DB 재고 | 최종 발급 확정 상태 |
| `ACTIVE + Redis stock=0` | 즉시 버그가 아님. DB 확정 + Scheduler 전이 필요 |
| Redis Stream DLQ | `coupon:issue:stream:dlq` |
| 관리자 DLQ | `issue_message.status=DLQ`. 서로 다른 DLQ |
| `SENT` Outbox | Kafka 발행은 됐지만 DB 최종 소비가 아직 끝나지 않을 수 있음 |
| Reset | Redis Stream/Kafka 메시지를 삭제하는 API가 아님 |
| Reset `force=true` | 정상 테스트 절차가 아님. 해당 회차 결과 신뢰 불가 |
| Pipeline Drain | Redis 확인 실패도 안전하게 Block |
| Pipeline Drain | Redis Stream은 전역이므로 다른 Coupon의 잔여 메시지도 영향을 줄 수 있음 |
| Pipeline Drain | Kafka 내부 상태를 완전히 증명하지 않으므로 Reset 전 Consumer Lag도 확인 |
| DLQ `reprocess` | API 성공 = 최종 발급 성공이 아님 |
| DLQ `abandon` | `ABANDONED + stock_restored_at IS NULL`이면 복구 미완료 |
| Scheduler | `@SpringBootTest`에서도 실행될 수 있음 |
| Reconciliation | ENDED + Pipeline Drain 완료가 선행 조건 |
| SSE | Native `EventSource`로 `X-ADMIN-KEY`를 보낼 수 없음 |
| SSE | 재연결 시 과거 Event Replay 없음 |
| SSE | 느린 Client는 Monitoring Event 일부를 잃을 수 있음 |
| Health API | 발급 파이프라인 정상 여부를 하나의 Health 응답만으로 판단하지 않음 |
| DB 직접 수정 | Redis/Outbox/상태 데이터를 건너뛰어 정합성을 깨뜨릴 수 있으므로 장애 복구 수단으로 사용하지 않음 |

---

## 장애 발생 시 권장 확인 순서

쿠폰 발급 관련 장애는 다음 순서로 확인합니다.

```text
1. docker compose ps
        ↓
2. Application Health / Log
        ↓
3. Redis Stream
   - XINFO GROUPS
   - XPENDING
        ↓
4. Outbox
   - PENDING / FAILED / SENT / DLQ
        ↓
5. Kafka
   - Broker
   - Consumer Lag
        ↓
6. DB
   - coupon_issue
   - coupon_stock
   - coupon_issue_history
        ↓
7. Idempotency 상태
        ↓
8. DLQ / Reconciliation
        ↓
9. 동일한 방법으로 다시 검증
```

장애 분석 중 DB나 Redis 값을 임의로 먼저 수정하면 원래 실패 원인을 잃어버리고 정합성을 더 깨뜨릴 수 있습니다.

**진단 → 기존 자동 복구 확인 → 관리자 복구 기능 사용 → 최종 검증** 순서를 유지합니다.

---

## 에스컬레이션 — 수집할 정보

위 절차로 해결되지 않으면 다음을 수집해 담당자에게 전달합니다.

```bash
# 인프라 상태
docker compose ps
docker logs --tail=200 petcoupon-kafka
docker logs --tail=200 petcoupon-redis

# Redis Stream
docker exec petcoupon-redis redis-cli XINFO GROUPS coupon:issue:stream
docker exec petcoupon-redis redis-cli XPENDING coupon:issue:stream coupon-issue-group
docker exec petcoupon-redis redis-cli XLEN coupon:issue:stream:dlq

# Kafka Consumer Lag
docker exec petcoupon-kafka \
  /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --group petcoupon --describe

# 애플리케이션 지표
curl -s http://localhost:8080/actuator/health
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.pending
```

```sql
-- Outbox 상태 분포와 실패 원인
SELECT status, COUNT(*) FROM issue_message GROUP BY status;

SELECT message_id, coupon_id, status, retry_count, last_error, created_at
FROM issue_message
WHERE status IN ('FAILED', 'DLQ', 'ABANDONED')
ORDER BY message_id DESC
LIMIT 50;
```

애플리케이션 로그 파일이 필요하면 `LOG_FILE` 환경변수를 지정해 재현합니다.

```bash
LOG_FILE=logs/petcoupon.log ./gradlew bootRun
```

담당 영역별 연락 대상은 [`contributors.md`](contributors.md)를 참고합니다.
