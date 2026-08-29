---
title: Contributors
type: reference
audience: backend-developer
owner: petcoupon-backend-team
status: maintained
last_verified: 2026-08-28
---

# Contributors

PetCoupon Backend의 팀원별 담당 영역과 주요 기술적 기여를 정리합니다.

단순 작업 목록보다는 각 팀원이 담당한 문제, 구현 방식, 주요 설계 판단과 검증 내용을 중심으로 작성했습니다.

> **이 문서를 쓰는 법**
>
> 코드를 고치다 막혔거나 테스트가 깨졌을 때 **누구에게 물어봐야 하는지** 찾는 용도입니다.
> 코드 위치는 [`architecture.md`](architecture.md)의 Code Map, 장애 진단 절차는 [`troubleshooting.md`](troubleshooting.md)를 함께 참고합니다.

---

## 역할 요약

| Contributor | 역할 |
|---|---|
| [`rien00`](#rien00) | 신청 API · Idempotency |
| [`tnqlsqkr`](#tnqlsqkr) | Redis · Lua · 동시성 |
| [`mercy0704`](#mercy0704) | Kafka · Outbox · DLQ |
| [`shin838`](#shin838) | 쿠폰 상태 · 정합성 · Batch |
| [`seyeonham`](#seyeonham) | 부하 테스트 · 테스트 인프라 |
| [`Catverdose`](#catverdose) | 이벤트·쿠폰 관리 · 관리자 운영 |

---

## 코드 영역별 담당

문제가 발생한 코드 위치에서 담당자를 찾습니다.

| 코드 영역 | 주요 위치 | 담당 |
|---|---|---|
| 발급 신청 API · 멱등성 | `coupon/controller/CouponController`, `idempotency/` | `rien00` |
| Redis Stream · Lua · 재고 선점 | `coupon/issue/{producer,consumer,service}`, `resources/lua/` | `tnqlsqkr` |
| Outbox · Kafka · DLQ | `messaging/`, `coupon/issue/consumer/CouponIssueEventConsumer`, `CouponIssuePersister` | `mercy0704` |
| 쿠폰 사용·취소·만료 · 정합성 Batch | `coupon/service/CouponIssue*Service`, `reconciliation/`, `dashboard/` | `shin838` |
| 부하 테스트 · 내부 API · 인프라 | `load-test/`, `internal/`, `docker-compose.yml`, `global/common/util/PiiMasker` | `seyeonham` |
| 이벤트·쿠폰 관리 · 관리자 인증 · 모니터링 | `event/`, `coupon/controller/Admin*`, `global/auth/`, `monitoring/` | `Catverdose` |

---

## 통합 테스트 시나리오 담당

**시나리오가 실패하면 그 기능의 담당자가 확인합니다.**

| 담당 | 시나리오 | 건수 |
|---|---|---|
| `rien00` | TC-07~10, 30, 33, 38, 43 | 8 |
| `tnqlsqkr` | TC-31~32, 40~42, 44, 90~94 | 10 |
| `mercy0704` | TC-70~76 | 7 |
| `shin838` | TC-11~12, 34~37, 45~46, 50~66, 85 | 24 |
| `seyeonham` | TC-55~56, 80~84 | 7 |
| `Catverdose` | TC-01~06, 20~29 | 16 |

시나리오 전체 내용은 [통합 테스트 시나리오](../load-test/docs/integration-test-scenario.md)에, 실행 결과는 [통합 테스트 결과](../load-test/docs/integration-test-result.md)에 있습니다.

테스트 코드에는 시나리오 ID를 주석으로 남깁니다.

```java
// TC-45: 동일 발급 건에 사용 동시 호출
@Test
void onlyOneUseSucceedsWhenCalledConcurrently() { ... }
```

---

<details id="rien00">
<summary><strong>rien00</strong> — 선착순 신청 API · Idempotency</summary>

### 담당 영역

선착순 쿠폰 신청 API와 Idempotency 처리를 담당했습니다.

### 주요 구현

- `Idempotency-Key` 기반 중복 요청 방지
- `IN_PROGRESS / SUCCEEDED / FAILED` 상태 관리
- 동일 Key의 다른 요청 재사용 검증
- 만료된 `IN_PROGRESS` 요청 reclaim
- Redis Stream 기반 비동기 신청 처리
- 발급 결과 Polling API
- Consumer 최종 결과와 Idempotency 상태 연결

### 주요 설계 포인트

- DB Unique Constraint를 중복 요청의 최종 판정자로 사용
- MySQL과 Redis의 Transaction Boundary 분리
- 비동기 최종 결과가 HTTP 임시 `202` 상태보다 우선
- 실패 유형에 따라 Replay와 Retry를 구분
- 동일 요청 재전송 시 저장된 결과 재현

### 주요 PR

`#15` `#27` `#48` `#67` `#93` `#110` `#136` `#184`

### 테스트 및 검증

동일 Idempotency-Key 동시 요청, Key 재사용, 만료 후 reclaim, 결과 Replay, HTTP와 Consumer 간 상태 갱신 경쟁 및 비동기 발급 통합 흐름을 검증했습니다.

</details>

---

<details id="tnqlsqkr">
<summary><strong>tnqlsqkr</strong> — Redis · Lua · 동시성 제어</summary>

### 담당 영역

Redis Stream과 Lua Script를 기반으로 선착순 발급의 핵심 동시성 처리와 장애 복구를 담당했습니다.

### 주요 구현

- Redis Stream Producer / Consumer
- Lua Script 기반 원자적 재고 차감
- 동일 사용자 중복 신청 방지
- Redis 기반 발급 순번 생성
- 동일 requestId 재처리 방어
- Redis 재고 보상 Lua
- Pending Message Recovery
- Redis Stream DLQ
- Consumer Exponential Backoff

### 주요 설계 포인트

- 재고·중복 사용자·순번 처리를 하나의 Lua 실행으로 구성
- 동일 requestId에는 기존 sequenceNo 반환
- Redis Cluster Hash Tag 적용
- Outbox 저장 이후에만 Stream ACK
- 반복 실패 메시지를 Pending/DLQ 기반으로 복구
- 재고 복구 시 글로벌 sequence는 되감지 않음

### 주요 PR

`#25` `#32` `#49` `#54` `#66` `#78` `#120` `#134` `#169`

### 테스트 및 검증

동시 재고 차감, 동일 사용자 신청, 순번 충돌, 동일 요청 재처리, Pending 회수, DLQ 이동 및 Redis 장애 이후 Consumer 복구를 검증했습니다.

</details>

---

<details id="mercy0704">
<summary><strong>mercy0704</strong> — Kafka · Outbox · DLQ</summary>

### 담당 영역

Redis에서 판정된 발급 요청을 Kafka로 전달하고 MySQL에 최종 확정하는 비동기 처리 구간과 실패 복구를 담당했습니다.

### 주요 구현

- Outbox → Kafka 발행
- Kafka Consumer 발급 확정
- Transactional Persister 분리
- Kafka 중복 전달 방어
- Retry / Kafka DLQ
- `SENT / CONSUMED` 상태 구분
- 관리자 DLQ 재처리
- DLQ Abandon 및 Redis 재고 보상
- 발급 완료 Notification Mock

### 주요 설계 포인트

- Kafka at-least-once 기반 Consumer 멱등성
- Listener와 DB Transaction 책임 분리
- 발급 관련 MySQL 변경을 하나의 Transaction으로 구성
- Kafka 발행과 DB 처리 완료 상태 분리
- DLQ Reprocess 동시 요청에 CAS 적용
- Abandon은 DB 선점 후 Redis 보상 수행

### 주요 PR

`#63` `#77` `#100` `#118` `#124` `#141`

### 테스트 및 검증

Kafka 재전달, Consumer Retry, Retry 초과 DLQ, 동일 DLQ 동시 재처리, Reprocess/Abandon 경쟁 및 Redis 재고 보상 재시도를 검증했습니다.

</details>

---

<details id="shin838">
<summary><strong>shin838</strong> — 쿠폰 상태 · 정합성 · Batch</summary>

### 담당 영역

발급 쿠폰의 사용·취소·만료 상태 관리와 동시성 제어, 전체 발급 파이프라인의 정합성 검증을 담당했습니다.

### 주요 구현

- `ISSUED → USED` / `USED → ISSUED` 상태 전이
- Conditional Update 기반 동시성 제어
- CouponIssue History 관리
- 쿠폰 만료 Batch
- Cross-System Reconciliation
- Spring Batch 기반 대량 정합성 검증
- Batch Restart / Checkpoint
- 정합성 검증 Scheduler
- 관리자 Dashboard / 통계

### 주요 설계 포인트

- 상태 변경과 History를 동일 Transaction으로 처리
- 동시 상태 변경은 Conditional Update로 하나만 성공
- 대량 검증에 Chunk 기반 Batch 적용
- Key 기반 Paging으로 대량 조회
- Pipeline Drain 이후 정합성 검사
- Query 실행계획을 기준으로 검증 SQL 개선

### 주요 PR

`#10` `#28` `#40` `#53` `#62` `#70` `#133` `#155` `#158` `#173` `#178`

### 테스트 및 검증

동일 쿠폰 동시 사용, 사용/취소 경쟁, 상태와 이력 원자성, 만료 Batch, 정합성 규칙 탐지, 대량 Paging 및 Batch Restart를 검증했습니다.

</details>

---

<details id="seyeonham">
<summary><strong>seyeonham</strong> — 부하 테스트 · 대량 데이터 · 테스트 인프라</summary>

### 담당 영역

대량 요청 환경에서 선착순 시스템을 재현하고 측정하기 위한 테스트 인프라와 결과 검증 체계를 담당했습니다.

### 주요 구현

- Docker Compose 로컬 인프라
- 100만 사용자 · 300만 CouponIssue Seed
- 부하 테스트 Reset API
- Pipeline Drain 검사
- k6 Burst / Rate 시나리오
- Idempotency 부하 시나리오
- 결과 정합성 검증 SQL
- 전체 통합 테스트 시나리오
- 개인정보 마스킹 · 시연 Setup 자동화

### 주요 설계 포인트

- `202 Accepted` 접수 성능과 최종 발급 결과를 분리해 검증
- 테스트 회차별 DB/Redis 상태 격리
- 초기화 전 Pipeline Drain 확인
- 잘못된 k6 설정을 Setup 단계에서 차단
- 실제 API 기반 Fixture와 대량 Seed의 역할 분리
- 부하 결과를 SQL로 후검증

### 주요 PR

`#2` `#37` `#45` `#56` `#92` `#102` `#123` `#138` `#140` `#143` `#168` `#176` `#187`

### 테스트 및 검증

Burst, Constant Arrival Rate, 동일 사용자 동시 요청, Idempotency 재전송, 초과 발급, 1인 1매, 순번 정합성, Outbox Drain 및 DB 재고 일치를 검증했습니다.

</details>

---

<details id="catverdose">
<summary><strong>Catverdose</strong> — 이벤트·쿠폰 관리 · 관리자 운영</summary>

### 담당 영역

이벤트와 쿠폰의 생성·조회·수정 및 Lifecycle 관리와 관리자 운영 기능을 담당했습니다.

### 주요 구현

- 이벤트 / 쿠폰 생성 및 정책 검증
- 이벤트 수정 및 상태 전이
- 이벤트 / 쿠폰 상태 Scheduler
- 관리자 쿠폰 수정 동시성 제어
- 공개 / 관리자 이벤트 조회
- 관리자 쿠폰 Pagination / Filtering
- DB 확정 재고 기반 `SOLD_OUT`
- 관리자 Session 인증
- WARN/ERROR SSE 실시간 모니터링

### 주요 설계 포인트

- 일반 정보 수정과 상태 Transition 책임 분리
- 이벤트 상태 변경은 Conditional Update, 쿠폰 수정은 Pessimistic Lock으로 경합 제어
- Lock 순서를 `coupon → coupon_stock`으로 통일
- 목록은 DB 확정 재고, 실시간 조회는 Redis 재고 사용
- 관리자 Session Token을 Hash 형태로 Redis 저장하고 설정 누락 시 Fail-Closed
- SSE는 Subscriber별 Queue/Virtual Thread로 Slow Client를 격리

### 주요 PR

`#9` `#13` `#17` `#24` `#50` `#68` `#74` `#83` `#87` `#101` `#108` `#116` `#126` `#135` `#145` `#147` `#152` `#179` `#182`

### 테스트 및 검증

이벤트·쿠폰 Validation, 상태 Transition, 관리자/Scheduler 경쟁, 쿠폰 수정/발급 경쟁, Redis 초기화 실패 Rollback, 관리자 인증, `SOLD_OUT` 전이 및 SSE 연결·Heartbeat·Slow Subscriber 격리를 검증했습니다.

</details>

---

## 커밋 이력을 조회할 때

일부 팀원은 로컬 Git 설정이 달라 **커밋 author 이름이 GitHub 핸들과 다르게 기록된 구간**이 있습니다. 기여도를 커밋 수로 집계할 때는 author 이름이 아니라 이메일을 기준으로 합칩니다.

```bash
git log --format='%ae' | sort | uniq -c | sort -rn
```
