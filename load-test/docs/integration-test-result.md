# 통합 테스트 실행 결과

시나리오 정의는 [`integration-test-scenario.md`](./integration-test-scenario.md)에 있다. 이 문서는 **그 시나리오를 실제로 돌린 결과만** 기록한다. 기대값이 바뀌면 시나리오 문서를 고치고, 이 문서는 회차마다 덧쓴다.

| 항목 | 값 |
| --- | --- |
| 대상 | `dev` (#111 · #125 · #136 · #137 · #149 · #157 머지 후) |
| 실행 위치 | 로컬 (MySQL 8.0 · Redis 7.2 · Kafka 3.7, 전부 Docker) |
| 실행자 | 함세연 |
| 최종 갱신 | 2026-08-27 |

---

## 1. 실행 환경

C·G 구간(TC-41 · 91 · 94)은 동시 150~200건이라 아래 설정으로 앱을 띄운다. 기본 커넥션 풀(10)로 TC-41을 돌리면 **200건 중 191건이 500**으로 떨어진다(실측).

```powershell
$env:DB_POOL_SIZE="100"; $env:TOMCAT_MAX_THREADS="400"; .\gradlew bootRun
```

발급 시나리오는 **초기화 API를 먼저 부르지 않으면 진행되지 않는다.** Lua가 Redis의 `coupon:issue:stock` 키로 재고를 판정하는데 그 키를 채우는 곳이 이 API뿐이다.

```
POST /internal/coupons/{couponId}/reset   {"totalQuantity": N}
```

**이 문서의 D 구간은 정합성 배치를 손으로 트리거해서 실행한 결과다.** 이후 `#155` 로 자동 스케줄러(`ReconciliationScheduler`)가 들어왔는데, 여기 기록된 수치에는 그게 관여하지 않았다. 아래 §6 에 부하 테스트 때 주의할 점을 적어뒀다.

---

## 2. 판정 기준

| 표기 | 뜻 |
| --- | --- |
| ✅ | 기대 결과와 일치 |
| ❌ | 불일치 — 아래 §5에 원인 기록 |
| ⏸ | 선행 조건이 안 갖춰져 실행 못 함 |
| — | 미실행 |

**초과 발급이 1건이라도 나오면 전체 실패로 본다.**

---

## 3. 요약

| 구간 | 전체 | ✅ | ❌ | ⏸ | — |
| --- | --- | --- | --- | --- | --- |
| A. 정상 흐름 | 17 | 17 | 0 | 0 | 0 |
| B. 예외 흐름 | 19 | 19 | 0 | 0 | 0 |
| C. 경계·동시성 | 7 | 7 | 0 | 0 | 0 |
| D. 배치·정합성 | 16 | 16 | 0 | 0 | 0 |
| E. 비동기 확정 | 10 | 10 | 0 | 0 | 0 |
| F. 대량 데이터 | 6 | 6 | 0 | 0 | 0 |
| G. 순서 보장 | 5 | 5 | 0 | 0 | 0 |
| **합계** | **80** | **80** | **0** | **0** | **0** |

> TC-63 · TC-70은 결번이라 집계에서 뺐다.

---

## 4. 구간별 결과

### A. 정상 흐름 — TC-01 ~ TC-17

| TC | 시나리오 | 결과 | 근거 |
| --- | --- | --- | --- |
| TC-01 | 이벤트 생성 | ✅ | 201 · `status=SCHEDULED` |
| TC-02 | 이벤트 이름 수정 | ✅ | 200 · 이름 변경 반영 |
| TC-03 | 이벤트 설명 비우기 | ✅ | 빈 문자열 전송 → `description` NULL |
| TC-04 | 이벤트 기간 수정 | ✅ | 200 · `openAt`·`closeAt` 반영 |
| TC-05 | 쿠폰 생성 | ✅ | 201 · `coupon_stock.remaining_quantity = 10` |
| TC-06 | 이벤트 오픈 | ✅ | 200 · `event_status_history`에 `SCHEDULED→OPEN` 정확히 1건 |
| TC-07 | 쿠폰 발급 신청 (접수) | ✅ | 202 · `status=WAITING` · Redis 재고 차감 |
| TC-08 | 비동기 확정 후 결과 조회 | ✅ | 폴링으로 `couponIssueId` 획득 → `GET /coupon-issues/{id}/status` → `ISSUED` |
| TC-09 | 발급 상세 조회 | ✅ | `couponCode`·`expiresAt` 포함 · `isUsable=true` |
| TC-10 | 내 발급 내역 목록 | ✅ | 200 · 발급 건 조회됨 |
| TC-11 | 쿠폰 사용 | ✅ | 200 · `status=USED` · `ISSUED→USED` 이력 1건 |
| TC-12 | 사용 취소 | ✅ | 200 · `status=ISSUED` 복귀 · `USED→ISSUED` 이력 1건 |
| TC-13 | 신청 결과 폴링 — 접수 완료·확정 전 | ✅ | **202 · `WAITING` 재현** (#125에서 정정한 기대값과 일치) |
| TC-14 | 신청 결과 폴링 — 확정 후 | ✅ | **200 · `sequenceNo` 1 · `couponIssueId` 채워짐**. 다만 `status` 가 `null` (§5 참고) |
| TC-15 | 신청 결과 폴링 — 없는 키 | ✅ | 404 `COUPON404-2` |
| TC-16 | 실시간 요청 현황 조회 | ✅ | `totalQuantity 10` · `issuedQuantity 3` · `remainingQuantity 7` · `initialized true` |
| TC-17 | 실시간 현황 — 재고 미초기화 | ✅ | `initialized false` · `issuedQuantity 0` · `remainingQuantity = totalQuantity`. 위험성이 실제로 드러남 (§5 참고) |

### B. 예외 흐름 — TC-20 ~ TC-38

| TC | 시나리오 | 결과 | 근거 |
| --- | --- | --- | --- |
| TC-20 | openAt > closeAt로 이벤트 생성 | ✅ | 400 `EVENT400-0` |
| TC-21 | 존재하지 않는 이벤트 수정 | ✅ | 404 `EVENT404-0` |
| TC-22 | 동일 상태로 변경 | ✅ | 400 `EVENT400-1` (SCHEDULED → SCHEDULED) |
| TC-23 | 전이 순서 위반 | ✅ | 400 `EVENT400-2` (SCHEDULED → CLOSED) |
| TC-24 | 역방향 전이 | ✅ | 400 `EVENT400-2` (CLOSED → OPEN) |
| TC-25 | 쿠폰 생성 불가 상태에서 생성 | ✅ | 400 `COUPON400-1` (OPEN 이벤트에 생성 시도) |
| TC-26 | 발급 종료가 시작보다 이전 | ✅ | 400 `COUPON400-2` |
| TC-27 | 발급 기간이 이벤트 기간 벗어남 | ✅ | 400 `COUPON400-3` |
| TC-28 | 정률 할인 정책 오류 | ✅ | 400 `COUPON400-4` (RATE 150%) |
| TC-29 | 정액 할인에 최대 할인 금액 | ✅ | 400 `COUPON400-5` |
| TC-30 | 존재하지 않는 쿠폰에 신청 | ✅ | 404 `COUPON404-0` |
| TC-31 | 동일 사용자 중복 신청 | ✅ | 접수는 202, 비동기 판정 후 멱등키에 409 `COUPON409-1` |
| TC-32 | 재고 소진 후 신청 | ✅ | 재고 1 소진 후 신청 → 409 `COUPON409-0` |
| TC-33 | 존재하지 않는 발급 건 조회 | ✅ | 404 `COUPON404-1` |
| TC-34 | 본인 쿠폰이 아닌 건을 사용 | ✅ | 403 `COUPON403-0` |
| TC-35 | 이미 사용한 쿠폰 재사용 | ✅ | 사용 200 후 재사용 → 409 `COUPON409-3` |
| TC-36 | 사용하지 않은 쿠폰 취소 | ✅ | 409 `COUPON409-3` |
| TC-37 | 만료된 쿠폰 사용 | ✅ | EXPIRED 건 사용 시도 → 409 `COUPON409-3` |
| TC-38 | 존재하지 않는 사용자의 발급 내역 | ✅ | 404 `USER404-0` |

### C. 경계·동시성 — TC-40 ~ TC-46

| TC | 시나리오 | 결과 | 근거 |
| --- | --- | --- | --- |
| TC-40 | 재고 1, 동시 2명 | ✅ | 발급 1 · 순번 1..1 · 재고 0 |
| TC-41 | 재고 100, 동시 200명 | ✅ | 발급 **100** · 순번 1..100 · 고유회원 100 · 재고 0 · 500 0건 |
| TC-42 | 같은 회원 동시 5회 | ✅ | 발급 **1** · 멱등키 `SUCCEEDED 1 / FAILED 4` |
| TC-43 | 동일 멱등키 재전송 | ✅ | 발급 **1** · 재고 1만 차감 (재현 응답 확인은 §5 참고) |
| TC-44 | 재고 0, 동시 50명 | ✅ | 1회차 발급 1 · 재고 0 → 2회차 **99건 전부 `COUPON409-0`**, `409-1` 0건 |
| TC-45 | 사용 동시 10요청 | ✅ | `CouponIssueConcurrencyIntegrationTest.onlyOneUseSucceedsWhenCalledConcurrently` 통과 (§5 참고) |
| TC-46 | 취소 동시 10요청 | ✅ | `CouponIssueConcurrencyIntegrationTest.onlyOneCancelSucceedsWhenCalledConcurrently` 통과 |

### D. 배치·정합성 — TC-50 ~ TC-66

| TC | 시나리오 | 결과 | 근거 |
| --- | --- | --- | --- |
| TC-50 | 쿠폰 만료 배치 | ✅ | `CouponExpireBatchServiceImplTest`로 대체 검증 (§5 참고) |
| TC-51 | 만료 대상 아닌 건 미변경 | ✅ | 동상 |
| TC-52 | 청크 경계 처리 | ✅ | 동상 (`chunk-size=3`) |
| TC-53 | 배치 재실행 | ✅ | 동상 |
| TC-54 | Redis ↔ DB 재고 정합성 | ✅ | 쿠폰 612: 9+1=10 / 쿠폰 1101: 7+3=10 — 둘 다 총재고와 일치 |
| TC-55 | 초기화 API 동작 | ✅ | 삭제 건수 응답(발급·이력·멱등키·메시지 각 3)이 실제와 일치, 재고 10 원복, `redisStock` 10 |
| TC-56 | 초기화 API 운영 차단 | ✅ | `prod` 기동 시 엔드포인트 미등록 → **404 `COMMON404-0`**. 1차에는 500 이 나왔고 `#157` 로 고친 뒤 재검증했다 (§5 참고) |
| TC-57 | 정합성 배치 — 정상 판정 | ✅ | 쿠폰 612 → `MATCHED` · `errorCount 0` · 상세 0건 · `stockRemaining 9 = redisRemaining 9` |
| TC-58 | 정합성 배치 — HISTORY_MISMATCH | ✅ | 상태만 USED로 조작 → 기대 `ISSUED` / 실제 `USED` 탐지 |
| TC-59 | 정합성 배치 — INVALID_STATUS | ✅ | `EXPIRED → USED` 이력 주입 → 허용되지 않은 전이로 탐지 |
| TC-60 | 정합성 배치 — DUPLICATE_ISSUE | ✅ | 유니크 제약 제거 후 동일 회원 중복 발급 주입 → 2건 탐지 |
| TC-61 | 정합성 배치 — 재현성 | ✅ | 쿠폰 612 재실행 → 리포트 77·78의 전 수치 동일 |
| TC-62 | 정합성 배치 — STOCK_MISMATCH | ✅ | Redis 재고를 7→5로 조작 → 기대 7 / 실제 5 탐지 |
| TC-64 | 정합성 배치 — SEQUENCE_GAP | ✅ | 순번 99 주입 → "4건 존재, 95개 번호 없음" 탐지 |
| TC-65 | 정합성 배치 — STOCK_NOT_RESTORED | ✅ | `#149` 머지 후 실행. `stock_restored_at` 을 NULL 로 만들어 복구 실패 재현 → 탐지. 정상 abandon 건은 오탐 없음 (§5 참고) |
| TC-66 | 정합성 배치 트리거 | ✅ | `POST /admin/coupons/{id}/reconcile` → 즉시 실행되고 리포트 생성 (`BATCH_JOB_EXECUTION` 신규 행 확인) |

### E. 비동기 확정 — TC-71 ~ TC-79 · TC-95

| TC | 시나리오 | 결과 | 근거 |
| --- | --- | --- | --- |
| TC-71 | Consumer 정상 처리 | ✅ | 발급 10건 전건 ISSUED · 순번 1~10 · Outbox 전건 `CONSUMED` · Kafka LAG 0 |
| TC-72 | Consumer 일시 실패 후 재시도 | ✅ | TC-73 과정에서 확인 — 재시도 후 `retry_count` 증가, 중복 저장 없음 (§5 참고) |
| TC-73 | 재시도 최종 실패 → DLQ 적재 | ✅ | `coupon_stock` 행 제거로 저장 실패 유도 → `status=DLQ`. **Redis 재고 복구 안 됨**(의도된 설계) |
| TC-74 | Kafka 발행 자체 실패 | ✅ | Kafka 중단 상태에서 **202 WAITING 66~69ms** 정상 응답, `issue_message` → `FAILED` 재시도 대상. 재기동 18초 만에 3건 전건 `CONSUMED`, 유실 0 (§5 참고) |
| TC-75 | Consumer 중복 전달 (멱등) | ✅ | 같은 `requestId` 2회 전달 → `coupon_issue` 1건만, `issued_quantity` 1 유지, 500 없음 |
| TC-76 | 처리 전 앱 종료 후 재기동 | ✅ | 접수 10건 중 **4건이 `SENT`(미처리) 상태에서 강제 종료** → 재기동 후 10건 전건 `CONSUMED`, 순번 1~10 무결, 유실 0 (§5 참고) |
| TC-77 | DLQ 목록 조회 | ✅ | `messageId`·`requestId`·`retryCount`·`lastError` 포함. 토큰 없으면 401 |
| TC-78 | DLQ 수동 재발행 | ✅ | 원인(재고 행) 제거 후 재발행 → `CONSUMED`, `coupon_issue` 1건 추가, `issued_quantity` 0→1 |
| TC-79 | DLQ 재발행 — 중복·잘못된 요청 방어 | ✅ | ① 동시 5요청 → **200 × 1 / `COUPON409-7` × 4** ② DLQ 아닌 건 → `COUPON409-7` ③ 없는 ID → `COUPON404-3` |
| TC-95 | 관리자 DLQ 포기(abandon) → 재고 보상 | ✅ | `restoreStatus=RESTORED` · Redis 재고 2→3 · applicants 3→2 · `status=ABANDONED` |

### F. 대량 데이터·개인정보 — TC-80 ~ TC-85

| TC | 시나리오 | 결과 | 근거 |
| --- | --- | --- | --- |
| TC-80 | 회원 100만 건 상태에서 발급 | ✅ | 회원 1,000,028 · 발급이력 2,500,002 상태에서 접수 **0.16~0.55초**(중앙값 0.26초), 발급 10건 · 순번 1~10 · Outbox 전건 `CONSUMED`. 소규모(TC-71) 대비 악화 없음 |
| TC-81 | 발급 이력 300만 건 상태에서 조회 | ✅ | 응답 **40~76ms**. `EXPLAIN` → `type=ref`, `key=user_id FK 인덱스`, `rows=5` — 풀스캔 없음. 다만 `Using filesort` (§5 참고) |
| TC-82 | 발급 이력 300만 건 상태에서 배치 | ✅ | 250만 건 테이블에서 **350,000건 만료 · 108.9초**(3,211건/초), 1,000건씩 개별 커밋. 배치 중 발급 API 10건 전부 202(122~575ms) — 락으로 막히지 않음 (§5 참고) |
| TC-83 | 대량 상태에서 초기화 API | ✅ | 발급 50만 + 이력 65만 삭제, **470초**(7분 50초) · HTTP 200 · 타임아웃 없음 (§5 참고) |
| TC-84 | 개인정보 마스킹 | ✅ | `notification_log.recipient_masked` 가 `010-****-6992` 형태, 평문 **0건**. 발급 목록·상세·실시간 현황 응답에 개인정보 필드 없음 |
| TC-85 | 정합성 배치 — 전체 쿠폰 커버리지 | ✅ | SEED 쿠폰 6개 전부 처리, 누락 없음. 합산 `totalCount` 2,500,000 · `errorCount` **0**. 총 약 10분. 250만인 이유와 `MISMATCHED` 사유는 §5 참고 |

### G. 선착순 순서 보장 — TC-90 ~ TC-94

| TC | 시나리오 | 결과 | 근거 |
| --- | --- | --- | --- |
| TC-90 | 순차 100건 순서 일치 | ✅ | 발급 100 · 순번 1..100 · 재고 0 |
| TC-91 | 동시 200건 순서 역전율 | ✅ | 발급 100 · 비교쌍 4,950 · **역전쌍 1,294 (역전율 26.14%)** · 순번 1..100 무결 PASS. Redis `sequence` 값 100을 `@expected_issued_count`로 넣어 **꼬리 유실까지 대조** |
| TC-92 | 단일 요청 전 구간 추적 | ✅ | `requestId` 하나로 7단계 통과 시각 전부 조회됨. 전체 1,476ms (§5 참고) |
| TC-93 | 저장 순서 역전 확인 | ✅ | **저장순서 역전 0건** · total 100 · distinct 100 · min 1 · max 100. 역전이 없는 이유는 §5 참고 |
| TC-94 | 재고 경계에서의 선착순 | ✅ | 재고 100 · 요청 150 → 발급 **100** · 순번 1..100 · 재고 0 |

---

## 5. 실패·보류 상세

### ✅ 300만 건 적재 완료 (2026-08-27 15:46 ~ 15:56, 약 10분)

`load-test/sql/seed_coupon_issues.sql` 실행. `#111` 인덱스가 이미 생성된 상태에서 넣어 `ALTER TABLE ADD INDEX` 가 300만 행을 대상으로 도는 일은 없었다.

| 항목 | 값 |
| --- | --- |
| `coupon_issue` | **3,000,001** (SEED 300만 + 기존 1) |
| `coupon_issue_history` | **3,900,001** |
| SEED 쿠폰 | 6개 × 50만 |
| 테이블 크기 | `coupon_issue` 데이터 357MB · 인덱스 592MB / `coupon_issue_history` 291MB · 228MB |

**데이터 품질**

| 확인 | 결과 |
| --- | --- |
| 쿠폰별 `total_quantity` = `issued_quantity` = 실제 행 수 | 6개 전부 500,000 일치 |
| `uk_issue_coupon_user` 위반 | **0건** |
| 회원별 보유량 분포 | 1장 199,999명 · 2장 200,001명 · 3~5장 각 200,000명 |
| 상태 분포 | `ISSUED` 2,100,001 · `USED` 600,000 · `EXPIRED` 300,000 |

회원별 보유량이 1~5장으로 갈려 있어 "내 쿠폰 목록 조회"에 편차가 있다. 쿠폰을 6개로 나눈 목적(`uk_issue_coupon_user` 때문에 쿠폰당 최대 100만 건)이 의도대로 달성됐다.

### ✅ 해소됨 — 멱등키 확정 누락 (`#136`)

발급이 DB까지 확정돼도 `idempotency_key` 가 `202 WAITING` 에 멈춰 있고 `couponIssueId`·`sequenceNo` 가 `NULL` 로 남던 문제가 `#136` 으로 해소됐다. 원인은 두 가지가 겹쳐 있었다.

1. `updateStatusByMessageKey` 의 `@Modifying(clearAutomatically = true)` 가 `persist()` 트랜잭션의 1차 캐시를 flush 전에 비워 `idempotency_key` 의 더티체킹 UPDATE 가 유실됐다.
2. 컨트롤러의 뒤늦은 `202` 쓰기가 파이프라인이 먼저 쓴 `200` 을 덮어썼다.

머지 후 확인한 결과 **`response_status` 가 `200`, `sequenceNo` 가 채워진다.** 이걸로 TC-08 · TC-13 ~ 15 가 열렸고 전부 통과했다.

**다만 `idempotency_key.coupon_issue_id` 컬럼은 여전히 `NULL` 이다.** 응답 본문에는 발급 정보가 들어가므로 조회·폴링에는 지장이 없지만, 멱등키에서 발급 건을 역참조하는 용도로는 못 쓴다.

### ⚠️ TC-14 — 확정 후 응답의 `status` 가 `null` 이다

```json
{"code":"200","result":{"status":null,"sequenceNo":1,"couponIssueId":6317771}}
```

발급 정보는 정상적으로 채워지는데 `status` 만 비어 있다. `#125` 리뷰에서 "API 계약을 확정해야 한다"고 짚었던 부분이 그대로 남아 있다.

**프론트가 `status` 로 발급 성공을 판단하면 동작하지 않는다.** `ISSUED` 를 내려줄지 정해야 한다.

계약을 정할 때 쓰이는 실제 값은 이렇다. `CouponIssueConverter` 기준이며, **`SUCCESS` 같은 값은 존재하지 않는다.**

| 시점 | `status` |
| --- | --- |
| 접수 응답(202) | `"WAITING"` 고정 |
| 확정 응답 | `ISSUED` · `USED` · `EXPIRED` (`IssueStatus` enum 이름) |

확정될 때까지는 `status` 대신 **`couponIssueId != null` 로 성공을 판정**하는 쪽이 안전하다.

### ⚠️ TC-17 — 미초기화 쿠폰이 "재고 가득"으로 보인다

`SEED-쿠폰-1`(발급 50만 완료, DB 잔여 0)을 조회한 결과다.

```json
{"totalQuantity":500000,"remainingQuantity":500000,"issuedQuantity":0,"initialized":false}
```

**DB 는 완전 소진인데 API 는 재고 만땅으로 응답한다.** Redis 키가 없으면 잔여를 DB 총재고로 채워 내보내기 때문이다.

`initialized: false` 를 함께 보지 않으면 다 팔린 쿠폰이 발급 가능해 보인다. `#125` 에서 정정한 설명이 실측으로 확인됐다.

### TC-65 — `#149` 수정이 오탐·미탐 양쪽으로 검증됨

`#149` 는 판정 기준을 `status='ABANDONED'` 에서 **`status='ABANDONED' AND stock_restored_at IS NULL`** 로 바꿨다. 두 방향을 다 확인했다.

| 상태 | 결과 |
| --- | --- |
| 정상 abandon (`stock_restored_at` 채워짐) | **탐지 안 함** — 오탐 없음 |
| `stock_restored_at` 을 NULL 로 주입 | **탐지함** — 미탐 없음 |

`status` 만 봤다면 정상적으로 복구된 절대다수를 전부 미복구로 오탐했을 것이다. 확인 후 주입한 값은 원복했다.

### 테스트로 대체 검증 — TC-45 · TC-46 · TC-50 ~ TC-53

**여기 묶인 TC 는 전부 실제로 테스트를 실행해서 통과를 확인한 것이다.** "테스트가 있으니 됐다"고 넘긴 게 아니다.

TC-45 · TC-46 은 `CouponIssueConcurrencyIntegrationTest`(#59) 2건 통과로 판정한다. 메서드 이름이 시나리오와 그대로 대응한다.

| TC | 대응 테스트 |
| --- | --- |
| TC-45 | `onlyOneUseSucceedsWhenCalledConcurrently` |
| TC-46 | `onlyOneCancelSucceedsWhenCalledConcurrently` |

TC-50 ~ TC-53의 **만료 배치는 외부에서 수동 실행할 방법이 없다.** `CouponExpireBatchServiceImpl.expireOverdueCoupons()` 가 `@Scheduled(cron = "0 0 1 * * *")` 로 고정돼 있고 트리거 API도 없다. 유일한 실행 경로가 `CouponExpireBatchServiceImplTest`(`@DataJpaTest`, 실제 MySQL 사용)라 이것으로 판정한다 — 3건 전부 통과.

| TC | 대응 테스트 |
| --- | --- |
| TC-50 · TC-51 | `expireOverdueCoupons_expiresOnlyIssuedAndPastDeadline` |
| TC-52 | `expireOverdueCoupons_processesAllRowsAcrossMultipleChunks` (`chunk-size=3`) |
| TC-53 | `expireOverdueCoupons_succeedsWhenNothingToExpire` |

운영에서 배치를 즉시 돌려야 할 상황이 생기면 트리거가 없다는 게 문제가 될 수 있다. 별도 이슈감이다.

### 실행 중 확인한 것 — 앱을 최신 코드로 다시 띄워야 했다

첫 TC-57 호출이 **75초 뒤 500을 뱉고 앱이 죽었다.** `reconciliation_report` 는 생겼는데 `BATCH_JOB_EXECUTION` 에는 기록이 없어서, `#111` 머지 전 빌드가 계속 떠 있던 것으로 확인됐다. 구버전은 50만 건을 통째로 로딩하는 구조다.

앱을 다시 띄운 뒤에는 `BATCH_JOB_EXECUTION` 에 신규 행이 남고 정상 응답했다. **소스를 머지한 뒤에는 앱을 반드시 재기동해야 한다.**

### 정합성 배치 처리 시간 (참고)

발급 50만 건 쿠폰(`SEED-쿠폰-2`) 1건 검증에 **약 70초**가 걸렸다. TC-85(전체 쿠폰 커버리지)는 SEED 쿠폰 6개 × 50만이라 단순 합산으로 **7분 안팎**을 예상해야 한다.

### ✅ TC-79 ① — 동시 재발행이 실제로 1건만 통과한다

리뷰에서 "`retryCount` 조건부 증가만으로는 동시 재발행을 1회로 제한할 수 없다"는 지적이 있었으나, 실제 동시 5요청 결과는 **200 한 건 · `COUPON409-7` 네 건**이었다.

`UPDATE ... WHERE message_id = ? AND status = 'DLQ' AND retry_count = ?` 가 CAS 라, 다섯 요청이 같은 `retryCount` 를 읽고 들어와도 행 잠금이 직렬화해서 첫 건만 1행을 갱신한다.

다만 **순차 재요청은 여전히 막지 않는다.** 재발행 성공 후에도 상태가 `DLQ` 로 남아, 원인을 안 고친 채 다시 누르면 `retry_count` 만 오르며 또 선점된다(실제로 1→3 으로 올랐다). 이중 발급은 `coupon_issue.request_id` 유니크가 막는다.

### DLQ 결함 주입 방법 — 순서가 중요하다

`coupon_stock` 행을 지워 `increaseIssuedQuantity()` 가 0행을 반환하게 만드는 방식이 가장 깔끔했다. FK 를 건드리지 않으면서 저장만 실패시킨다.

**단, 요청을 보낸 뒤에 지우면 늦다.** 파이프라인이 1~2초 안에 저장을 끝내버려서 첫 시도가 `CONSUMED` 로 성공했다. **재고 행을 먼저 지우고 요청해야** DLQ 로 간다.

### ⚠️ 초기화 API가 대량 데이터에서 8분 걸린다 — TC-83

50만 건 쿠폰(`SEED-쿠폰-6`)에 초기화 API를 호출하니 **470초(7분 50초)** 가 걸렸다. 발급 50만 + 이력 65만을 지우는 단일 요청이다.

기대 결과("정상 완료, 타임아웃 없음")는 충족한다 — HTTP 200 이고 끊기지 않았다. 다만 **부하 테스트 회차 사이에 돌리기엔 너무 길다.** 부하 테스트 대상 쿠폰은 재고 10,000 규모라 이만큼 걸리지 않지만, 회차를 반복하며 발급이 쌓이면 초기화 시간도 같이 늘어난다는 점은 감안해야 한다.

### TC-81 — 인덱스는 타지만 `Using filesort` 가 붙는다

```
type = ref    key = user_id FK 인덱스    rows = 5    Extra = Using filesort
```

300만 건에서 5행만 읽으므로 풀스캔은 아니다. 정렬이 `created_at DESC` 인데 인덱스가 `user_id` 만 덮어서 filesort 가 남는다.

지금은 회원별 보유량이 1~5장이라 무해하다. **한 회원이 수천 장을 갖는 상황이 생기면** 그때 `(user_id, created_at)` 복합 인덱스를 검토해야 한다.

### ✅ TC-82 — 대량 만료 배치 실측 (2026-08-27 21:28 ~ 21:30)

**어떻게 돌렸는가** — 만료 배치는 `@Scheduled(cron = "0 0 1 * * *")` 로만 도는 데다 수동 실행 API 가 없어서, 기동 중인 앱에서는 시작시킬 방법이 없다. 실행 중인 앱은 그대로 두고, 서비스 빈을 직접 호출하는 임시 테스트를 만들어 돌린 뒤 지웠다.

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CouponExpireBatchServiceImpl.class, CouponBatchSchedulerConfig.class})
// 테스트 트랜잭션을 끈다. 켜두면 TransactionTemplate 이 거기 합류해
// 청크가 전부 한 트랜잭션이 되고 끝나면 롤백돼서 운영과 다른 실행이 된다.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
```

**대상 데이터** — SEED 의 `expires_at` 이 2027년이라 만료 대상이 0건이었다. 쿠폰 1090 의 `ISSUED` 350,000건을 `expires_at = 2026-01-01` 로 바꿔 대상을 만들었다. 원래 값과 구분되는 시각을 골라, 실행 뒤 그 시각으로만 되돌려 원복했다.

| 항목 | 값 |
| --- | --- |
| 테이블 규모 | `coupon_issue` 2,500,026건 |
| 만료 처리 | 350,000건 |
| 소요 | **108,913ms** (약 1분 49초, 3,211건/초) |
| 청크 | 1,000건씩 **개별 커밋** — `innodb_trx.trx_rows_modified` 가 2,000(UPDATE 1,000 + 이력 1,000)에서 끊기며 반복 |
| 이력 | `coupon_issue_history` 350,000건 = 상태 변경 건수와 정확히 일치 |
| 잔여 | `ISSUED` 만료 대상 0건 |

**락 대기로 발급 API 가 막히지 않는가** — 배치가 도는 중에 발급 요청을 쏴서 응답 시간을 쟀다.

| 시점 | 요청 | 응답 시간 | HTTP |
| --- | --- | --- | --- |
| 배치 진행 중 | 10건 | 122 ~ 575ms | 전부 202 |
| 배치 종료 후 | 5건 | 102 ~ 224ms | 전부 202 |

느려지긴 하지만 막히지 않는다. **다만 배치 중 지연을 전부 락 경합으로 읽으면 안 된다** — 배치를 돌린 테스트 JVM 이 같은 노트북 CPU 를 쓰고 있었다. 분리해서 재려면 배치와 API 를 다른 장비에 두어야 한다.

실행 후 쿠폰 1090 은 `ISSUED 350,000 / USED 100,000 / EXPIRED 50,000` 으로, 배치가 넣은 이력 350,000건은 삭제해 원복했다.

**수동 트리거 부재는 그대로 남아 있다.** 이번엔 임시 테스트로 우회했지만 운영에서는 새벽 1시를 기다리는 것 말고 방법이 없다. 별도 이슈로 다룰 값어치가 있다.

### ✅ TC-74 — Kafka 중단 (2026-08-27 21:21 ~ 21:26)

`docker stop petcoupon-kafka` 후 신청 3건.

| 확인 항목 | 결과 |
| --- | --- |
| 응답 | **202 WAITING · 66 · 67 · 69ms** — Kafka 가 죽어도 응답 경로는 영향 없음 |
| Outbox | `PENDING` → `FAILED`, `retry_count` 증가 (재시도 대상에 들어감) |
| 재기동 후 | 21:25:17 기동 → 21:25:33~35 **3건 전건 `CONSUMED`**, 유실 0 |
| 순서 | `sequence_no` 1(878) · 2(892) · 3(946) — 접수 순서 유지 |

시나리오 기대는 전부 충족했다. 다만 실행 중에 **기대에 없던 것 두 가지**가 나왔다.

**① Kafka 가 죽으면 Outbox 발행이 메시지 1건당 60초씩 직렬로 막힌다.**

`application.properties` 에 `request.timeout.ms=5000` · `delivery.timeout.ms=10000` 은 있는데 **`max.block.ms` 가 없다**. 이 값은 브로커 메타데이터를 못 받을 때 `send()` 호출 자체가 붙잡혀 있는 시간이고, 앞의 두 타임아웃과는 다른 구간이라 기본값 60초가 그대로 적용된다.

`CouponIssueOutboxPublisher` 는 조회한 메시지를 스트림으로 돌며 `publish()` 를 부르는데, 그 안의 `kafkaTemplate.send()` 가 각각 60초씩 붙잡힌다. 실제로 3건 처리에 180초가 걸렸다(`retry_count` 가 1건씩 순차로 올라갔다). `batch-size=100` 이면 한 틱에 100분이다.

밀린 메시지가 사실상 진행되지 않으므로, 장애가 길어지면 `max-retry-count=5` 소진에도 한참 걸리고 DLQ 전이도 그만큼 늦어진다. `spring.kafka.producer.properties.max.block.ms` 를 `delivery.timeout.ms` 수준으로 낮추는 게 맞다. → **`#161`** (`chore/161-kafka-producer-max-block-ms`)

**② `last_error` 에 `Send failed` 만 남는다.**

`errorMessage()` 가 `throwable.getMessage()` 를 쓰는데, `KafkaProducerException` 의 메시지가 그 문자열이다. 진짜 원인인 `Topic ... not present in metadata after 60000 ms` 는 `getCause()` 쪽에 있어 DB 만 봐서는 무엇이 실패했는지 알 수 없다. 운영에서 DLQ 를 판단할 때 이 컬럼을 본다면 원인 체인까지 남겨야 한다. → **`#162`** (`fix/162-outbox-last-error-detail`)

### ✅ TC-76 — 처리 전 앱 강제 종료 후 재기동 (2026-08-27 21:36 ~ 21:37)

10건을 동시에 접수시키고 **1.0초 뒤**(마지막 응답 직후) `Stop-Process -Force` 로 앱을 죽였다. 강제 종료라 셧다운 훅도 돌지 않는다.

종료 직후 스냅샷이 이 TC 가 노린 상태를 정확히 잡았다.

| | 종료 직후 | 재기동 후 |
| --- | --- | --- |
| `issue_message` | `CONSUMED` 6 · **`SENT` 4** | `CONSUMED` 10 |
| `coupon_issue` | 6건 | **10건** |
| `issued_quantity` | 6 | 10 |

**`SENT` 4건은 Kafka 에는 실려 갔는데 컨슈머가 아직 DB 에 쓰지 못한 것이다.** 이 상태로 프로세스가 사라졌으니, 오프셋을 이어받지 못하면 그대로 유실된다.

재기동 후 `created_at` 이 두 덩어리로 갈린다.

```
sequence_no 1~6    21:36:24.68 ~ 21:36:25.99   (종료 전)
sequence_no 7~10   21:37:17.59 ~ 21:37:18.36   (재기동 후)
```

| 확인 항목 | 결과 |
| --- | --- |
| 유실 | **0** — 10건 전건 저장 |
| 순번 | 1~10, 고유 10개, 최대 10 — 빠짐·중복 없음 |
| 회원 | 10명 (중복 발급 없음) |
| 재고 | `issued_quantity` 10 / `total_quantity` 50 |

**순서까지 지켜졌다.** 밀린 4건이 재기동 후에도 7 · 8 · 9 · 10 순서 그대로 저장됐다 — 파티션 키가 `couponId` 라 같은 쿠폰의 이벤트가 한 파티션에 모이고, 그 안에서 오프셋 순으로 처리되기 때문이다.

### ✅ TC-56 — 1차 실패(500) → `#157` 수정 후 재검증

**1차 실행 — 차단은 되는데 404 가 아니라 500 이었다**

`SPRING_PROFILES_ACTIVE=prod` 로 기동해 초기화 API 를 불렀다.

| 프로파일 | 요청 | 1차 응답 |
| --- | --- | --- |
| `prod` | `POST /internal/coupons/1102/reset` | **500 `COMMON500-0`** |
| `prod` | `POST /internal/coupons/1102/nonexistent` (없는 경로) | 500 `COMMON500-0` |
| `prod` | `POST /totally/does/not/exist` (없는 경로) | 500 `COMMON500-0` |
| 기본 | `POST /internal/coupons/1102/reset` | 200 |

없는 경로 셋이 전부 같은 응답이므로 **`@Profile("!prod")` 로 엔드포인트가 등록되지 않은 것 자체는 맞았다.** 차단이라는 목적은 달성된 상태였고, 어긋난 건 상태 코드뿐이었다. 판정 기준이 "기대 결과와 일치"라 ❌ 로 기록했다.

원인은 `GlobalExceptionHandler` 의 마지막 핸들러였다.

```java
// 처리하지 않은 모든 예외 처리
@ExceptionHandler(Exception.class)
public ResponseEntity<CustomResponse<Void>> handleAllException(Exception ex) {
```

Spring Boot 4 는 매칭되는 핸들러가 없으면 `NoResourceFoundException` 을 던지는데, 이 catch-all 이 그것까지 삼켜 `INTERNAL_SERVER_ERROR` 로 바꿨다. **이 앱은 어떤 경로로도 404 를 낼 수 없는 상태였다** — TC-56 만의 문제가 아니라, 프론트가 "서버가 터졌다"와 "주소가 틀렸다"를 구분할 수 없다는 뜻이다.

**수정 — `#157`**

catch-all 앞에 `NoResourceFoundException` 핸들러를 두고 기존 `CommonErrorCode.NOT_FOUND`(`COMMON404-0`) 를 재사용한다. 로그는 서버 결함이 아니므로 스택 트레이스 없이 `warn` 으로 메서드와 경로만 남긴다.

**재검증 — 전부 404**

| 프로파일 | 요청 | 수정 후 |
| --- | --- | --- |
| `prod` | `POST /internal/coupons/1102/reset` | **404 `COMMON404-0`** |
| `prod` | `POST /totally/does/not/exist` | 404 `COMMON404-0` |
| 기본 | `POST /totally/does/not/exist` | 404 `COMMON404-0` |
| 기본 | `POST /coupons/1102/nonexistent` | 404 `COMMON404-0` |

회귀도 함께 봤고 이상 없다 — 정상 발급 202, 없는 쿠폰 `COUPON404-0`(도메인 404 는 뭉개지지 않는다), 미지원 메서드 405, 기본 프로파일 초기화 API 200.

회귀 테스트 `GlobalExceptionHandlerNotFoundTest` 2건을 함께 넣었다. **핸들러를 다시 빼고 돌려 `Status expected:<404> but was:<500>` 으로 깨지는 것까지 확인했다** — 통과만 보고 넘기면 회귀 테스트라 부를 수 없다.

### ⚠️ 초기화 API 가 `SOLD_OUT` 쿠폰의 상태를 되돌리지 않는다

TC-74 준비 중에 확인했다. `POST /internal/coupons/{id}/reset` 은 재고·발급·멱등키·Redis 키를 전부 되돌리는데 **`coupon.status` 는 건드리지 않는다.** 쿠폰 1102 는 초기화 후 `remainingQuantity 50 · redisStock 50` 인데도 `status` 가 `SOLD_OUT` 으로 남아 있었다.

`CouponStatusSchedulerServiceImpl` 의 전이는 `ACTIVE → SOLD_OUT` 단방향이라 스케줄러도 되돌려주지 않는다.

**발급을 막지는 않는다 — 재현해서 확인했다.** 쿠폰 1102 를 `SOLD_OUT` 인 채로 두고 신청했더니 그대로 발급됐다.

```
coupon.status = SOLD_OUT, 재고 20 초기화 후 신청
  → 202 WAITING → coupon_issue 1건 ISSUED, sequence_no 1
  → issued_quantity 1, idempotency_key SUCCEEDED / 200
```

`CouponIssueServiceImpl.issue()` 가 보는 건 `couponRepository.existsById(couponId)` 뿐이고, 재고 판정은 Redis Lua 가 `coupon:issue:stock` 키로 한다. **발급 경로는 `coupon.status` 를 아예 읽지 않는다.**

따라서 부하 테스트 2회차를 막지 않는다. 남는 영향은 조회 쪽이다 — `coupon.status` 는 목록·상세 응답에 그대로 나가고 목록 필터의 기준이므로, 초기화한 쿠폰이 화면에서는 계속 품절로 보인다. 초기화 대상에 `coupon.status` 를 넣는 게 맞지만 **급한 건 아니다.**

`#160` 에서 발급 기간에 맞춰 `READY`/`ACTIVE`/`ENDED` 로 되돌리도록 수정 중이다(이 문서 기준 미머지).

### TC-85 상세 — 커버리지는 통과, 합산이 250만인 이유

| 리포트 | 쿠폰 | result | totalCount | errorCount | 소요 |
| --- | --- | --- | --- | --- | --- |
| 83 | 1090 | MISMATCHED | 500,000 | 0 | 165초 |
| 84 | 1091 | MISMATCHED | 500,000 | 0 | 85초 |
| 85 | 1092 | MISMATCHED | 500,000 | 0 | 127초 |
| 86 | 1093 | MISMATCHED | 500,000 | 0 | 168초 |
| 87 | 1094 | MISMATCHED | 500,000 | 0 | 93초 |
| 88 | 1095 | MATCHED | **0** | 0 | 3초 |
| | **합계** | | **2,500,000** | **0** | 약 10분 |

**누락 없이 6개 전부 처리됐다.** 발급 건 단위 오류는 250만 건 어디에도 없다(`errorCount` 전부 0).

**합산이 300만이 아닌 이유** — TC-83 에서 `SEED-쿠폰-6`(1095)에 초기화 API 를 걸어 그 50만 건을 지웠기 때문이다. 실행 순서 때문이지 시스템 결함이 아니다. 다음 회차에 TC-85 를 먼저 돌리거나, TC-83 을 SEED 가 아닌 별도 쿠폰으로 하면 300만이 나온다.

### ⚠️ 발견 — 끝난 쿠폰이 항상 `MISMATCHED` 로 판정된다

`MISMATCHED` 5건의 사유가 전부 같다.

```
STOCK_MISMATCH  ×5   expected="0"  actual="Redis 키 없음"
```

SEED 쿠폰은 SQL 로 직접 만든 과거 데이터라 Redis 키가 애초에 없다. 배치가 `DB 재고 vs Redis 재고` 를 비교하다 **키 부재를 불일치로 잡는다.**

이건 SEED 데이터만의 문제가 아니다. **정합성 배치는 `ENDED` 쿠폰만 대상으로 하는데, 발급이 끝나고 Redis 키가 정리된 쿠폰은 정상 상태다.** 지금 로직이면 그런 쿠폰이 전부 `MISMATCHED` 로 나온다 — 운영에서 일반적으로 발생하는 상황이다.

`STOCK_MISMATCH` 가 **"Redis 키 없음"과 "값이 다름"을 구분**해야 할 것으로 보인다. `#149` 로 `STOCK_NOT_RESTORED` 를 손볼 때 함께 보자고 적어뒀는데 그쪽만 머지됐으므로, 이건 별도 이슈로 남아 있다.

**고칠 때 두 가지를 조심해야 한다(리뷰에서 나온 제안을 검토하며 확인한 것).**

첫째, **"쿠폰이 `ENDED` 면 정상으로 넘긴다"는 조건은 쓸 수 없다.** 정합성 배치는 애초에 `ENDED` 쿠폰만 대상으로 하기 때문이다 — `ReconciliationScheduler` 가 `findCouponIdsByStatus(ENDED)` 로 대상을 뽑고, 수동 트리거도 `PreconditionCheckTasklet` 에서 `COUPON409-9`("발급이 종료된 쿠폰만 정합성 검증할 수 있습니다")로 막는다. 대상이 전부 `ENDED` 라 그 조건을 넣으면 `STOCK_MISMATCH` 검사가 통째로 꺼진다.

둘째, **재고 키에는 TTL 이 없다.** 코드 어디에도 만료를 걸지 않는다(TTL 을 쓰는 건 관리자 세션 키뿐이다). 따라서 "캐시가 만료돼 사라진 정상 상황"은 존재하지 않고, 키가 없어지는 경로는 둘뿐이다.

```
① 초기화 API(/internal/coupons/{id}/reset) 가 명시적으로 지움
② Redis 재시작 — 영속화 설정에 따라 통째로 유실
```

②는 재고 판정의 근거가 사라진 것이라 **운영에서 그냥 넘겨선 안 되는 상황**이다.

그래서 방향은 "정상 처리"가 아니라 **분리**다.

| 상황 | 처리 |
| --- | --- |
| Redis 키 없음 | **검증 불가**로 별도 사유 — 대조할 대상이 없다는 사실 자체를 남긴다 |
| 키는 있고 값이 다름 | `STOCK_MISMATCH` — 실제 불일치 |

이렇게 나눠야 SEED 데이터 같은 노이즈를 걷어내면서 진짜 키 유실도 신호로 남는다. 오탐을 없애되 미탐을 만들지 않는 것이 요점이며, `#149`(TC-65)에서 오탐·미탐 양쪽을 다 확인했던 것과 같은 기준이다.

### TC-92 — 단일 요청 전 구간 통과 시각

로그 파일을 켜고(`LOG_FILE=logs/petcoupon.log`) `requestId` 로 grep 한 결과다.

| 시각 | 단계 | 직전 대비 |
| --- | --- | --- |
| 21:04:41.283 | 접수 (컨트롤러) | — |
| 21:04:41.322 | Stream 수신 | +39ms |
| 21:04:41.345 | Lua 판정 (`SUCCESS`, 순번 1) | +23ms |
| 21:04:41.348 | 선점 | +3ms |
| 21:04:42.635 | Kafka 발행 성공 | **+1,287ms** |
| 21:04:42.640 | Kafka 수신 | +5ms |
| 21:04:42.759 | DB 저장완료 | +119ms |
| | **전체** | **1,476ms** |

**병목은 선점 → Kafka 발행 구간(전체의 87%)이다.** Outbox 폴러가 1초 주기(`coupon.issue.outbox.publish-fixed-delay-ms=1000`)라 단건 요청은 그만큼 대기한다. 부하 상황에서는 폴러가 배치로 집어가므로 건당 대기가 줄어든다.

**선착순 판정 자체는 65ms 안에 끝난다**(접수 → 선점). 그 뒤는 전부 비동기 저장 대기다.

### TC-93 — 저장 순서 역전이 0건인 이유

기대값은 "달라도 정상"인데 실제로는 **한 건도 어긋나지 않았다**(`created_at` 순서 = `sequence_no` 순서).

Kafka 파티션 키가 `couponId` 라 **같은 쿠폰의 이벤트가 전부 같은 파티션**으로 가고, 파티션 안에서는 전달 순서가 유지되기 때문이다. `#125` 에서 "파티션 3개로 분산된다"고 잘못 적혀 있던 것을 `couponId` 키 기준으로 정정했는데, 그게 실측으로 확인된 셈이다.

**다만 "항상 0" 이라고 단정할 수는 없다.** 저장은 건마다 별도 트랜잭션이라 커밋 순서가 어긋날 여지는 남아 있다. 판정 기준은 어디까지나 `sequence_no` 1..N 무결성이다.

### ⚠️ 판정 시 주의 — `errorCount` 와 `result` 는 다른 것을 센다

`errorCount` 는 **발급 건 단위** 오류만 센다. 쿠폰 단위 오류(`STOCK_MISMATCH` · `SEQUENCE_GAP`)는 `errorCount` 에 안 잡힌다.

TC-62 실행 결과가 `errorCount 0` · `verificationDetailCount 1` · `result MISMATCHED` 였다. **정합성 판정은 `result` 로 해야 하고 `errorCount` 로 하면 안 된다.**

---

## 6. 부하 테스트 착수 판정

| 조건 | 상태 |
| --- | --- |
| 초과 발급 0건 | ✅ (C·G 구간 실측) |
| 1인 1매 위반 0건 | ✅ (TC-42 · TC-44) |
| 순번 빠짐·중복 0건 | ✅ (TC-41 · 90 · 94) |
| 300만 건 적재 | ✅ (2026-08-27, §5 참고) |
| 미실행 TC | **0건** — 80건 전부 실행 |
| A 구간 | ✅ 17/17 |
| B 구간 | ✅ 19/19 |
| C 구간 | ✅ 7/7 |
| D 구간 | ✅ 16/16 (TC-56 은 `#157` 수정 후 재검증) |
| E 구간 | ✅ 10/10 |
| F 구간 | ✅ 6/6 |
| G 구간 | ✅ 5/5 |
| 장애 복구 시 유실 0건 | ✅ (TC-74 Kafka 중단 · TC-76 앱 강제 종료) |

**판정** — 착수 가능.

핵심 조건인 **초과 발급 0 · 1인 1매 위반 0 · 순번 무결 · 장애 시 유실 0** 이 전부 실측으로 확인됐다. 실행 중 유일하게 어긋났던 TC-56 도 `#157` 로 고쳐 재검증했으므로 **80 건 전건 통과**다.

### ⚠️ 측정 전에 정합성 자동 스케줄러를 꺼야 한다

`#155` 로 들어온 `ReconciliationScheduler` 는 **기본값이 켜짐**이다(`matchIfMissing = true`, `application.properties` 에 항목 없음). 기동 30분 뒤부터 30분 간격으로 `ENDED` 쿠폰을 전부 순회하며 정합성 배치를 돌린다.

현재 DB 의 `ENDED` 쿠폰은 이렇다.

| 쿠폰 | 발급 건수 |
| --- | --- |
| SEED-쿠폰-1 ~ 5 | 각 500,000 |
| SEED-쿠폰-6 | 0 |
| 612 · 1101 | 각 1 |

§5 에 기록한 실측이 **50만 건 쿠폰 1개당 약 70초**이므로, 한 번 깨어날 때마다 **6분 안팎의 배치가 통째로 끼어든다.** 측정 중에 이게 돌면 응답 시간과 커넥션 풀이 그 영향을 받아 결과를 믿을 수 없다.

부하 테스트를 돌리는 회차에서는 꺼야 한다.

```powershell
$env:COUPON_RECONCILIATION_SCHEDULER_ENABLED="false"; .\gradlew bootRun
```

**환경변수는 원래부터 먹었다** — `@ConditionalOnProperty` 가 `Environment` 를 읽고 Spring Boot 의 relaxed binding 이 `COUPON_RECONCILIATION_SCHEDULER_ENABLED` 를 `coupon.reconciliation.scheduler.enabled` 로 매핑해 주기 때문이다. 다만 `application.properties` 만 훑는 사람은 **그런 스위치가 있다는 것 자체를 모른다.** 그래서 다른 스케줄러(`event.status.scheduler.enabled`)와 같은 자리에 기본값과 함께 노출해 뒀다(리뷰 반영).

**부하 테스트를 막는 항목은 없다.** 실행 중 관찰한 것들은 전부 부하 경로 밖이거나 조건부다.

| 항목 | 부하 테스트 영향 | 처리 |
| --- | --- | --- |
| `max.block.ms` 미설정 (기본 60초) | Kafka 가 **끊겼을 때만** 발동. 정상 브로커에서는 메타데이터가 캐시돼 있어 걸리지 않는다 | **`#161`** 진행 중 |
| `last_error` 가 `Send failed` 뿐 | 없음 | **`#162`** 진행 중 |
| 초기화 API 가 `coupon.status` 미원복 | **없음** — 발급 경로가 `coupon.status` 를 읽지 않는다(§5 재현 확인) | **`#160`** 진행 중 |
| `STOCK_MISMATCH` 가 "키 없음"과 "값 다름"을 안 가림 | 없음 | 이슈 미등록 (§5 에 방향 기록) |
| 만료 배치 수동 트리거 부재 | 없음 | 이슈 미등록. TC-82 는 임시 테스트로 우회 |
| TC-14 확정 응답의 `status` 가 `null` | 없음 | 이슈 미등록. 프론트 연동 전 계약 확정 필요 |
| 정합성 자동 스케줄러 기본 켜짐 | **있음** — 30분마다 6분짜리 배치가 끼어든다 | 스위치 노출 완료. 측정 회차에서는 끈다(위 참고) |

**프론트 연동 전에 남은 것은 CORS 하나다.**

| 항목 | 상태 |
| --- | --- |
| 없는 경로가 404 가 아니라 500 | ✅ `#157` 로 해소 (§5) |
| CORS 헤더 없음 | ⚠️ 미해소 — 브라우저에서 직접 호출하면 전부 차단된다(프록시를 쓰지 않는 경우) |
