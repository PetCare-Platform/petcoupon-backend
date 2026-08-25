# 통합 테스트 시나리오

## 1. 목적과 범위

각 담당자가 구현한 기능이 **하나의 흐름으로 이어지는지** 확인한다. 개별 단위 테스트가 아니라 이벤트 생성부터 쿠폰 사용·만료·정합성 검증까지 실제 API를 호출해 검증한다.

| 구분 | 통합 테스트 | 부하 테스트 |
| --- | --- | --- |
| 확인하는 것 | 기능이 끝까지 이어지는가 | 규모에서 버티는가 |
| 요청 규모 | 1~200건 | 20,000건 |
| 판정 기준 | 기대값과 일치하는가 | TPS, p95, 정합성 |
| 실행 순서 | 먼저 | 통합 테스트 통과 후 |

## 2. 사전 조건

### 환경

| 항목 | 조건 |
| --- | --- |
| 실행 위치 | 로컬 (`docker compose up -d` — MySQL 8.0, Redis 7.2) |
| 애플리케이션 | 단일 인스턴스, `dev` 브랜치 최신 |
| 초기 데이터 | 회원 더미데이터 적재 완료 (`load-test/sql/seed_users.sql`) |

### 매 시나리오 시작 전 초기화

모든 시나리오는 아래 API로 상태를 초기화한 뒤 시작한다. 시나리오 간 순서에 의존하지 않도록 하기 위함이다.

| API | 동작 |
| --- | --- |
| `POST /internal/coupons/{couponId}/reset` | 발급 이력·멱등키·알림·메시지·검증 결과 삭제, 재고를 지정 수량으로 원복 |

> Redis 키(`stock`, `applicants`, `sequence`, `request-sequence`)는 아직 초기화 대상에 포함되지 않는다. 반복 실행 전까지 별도 삭제가 필요하다.

## 3. 시나리오 목록

**담당**은 해당 기능의 구현 담당자를 뜻한다. 실행은 담당6이 하지만, 시나리오가 실패하면 그 기능의 담당자가 확인한다. 각자 아래 표에서 자기 시나리오를 확인하고, 커버되지 않는 항목이 있으면 알려준다.

| 담당 | 시나리오 | 개수 |
| --- | --- | --- |
| 전송흔 | TC-01 ~ 06, TC-20 ~ 29 | 16 |
| 박신형 | TC-11 ~ 12, TC-34 ~ 37, TC-45 ~ 46, TC-50 ~ 54, TC-57 ~ 66, TC-85 | 24 |
| 박수빈 | TC-31 ~ 32, TC-40 ~ 42, TC-44, TC-90 ~ 91, TC-93 ~ 94 | 10 |
| 이성집 | TC-07 ~ 10, TC-30, TC-33, TC-38, TC-43 | 8 |
| 정자비 | TC-70 ~ 76 | 7 |
| 함세연 | TC-55 ~ 56, TC-80 ~ 84 | 7 |
| 공통 | TC-92 | 1 |

`공통`은 여러 담당자의 코드가 함께 있어야 성립하는 시나리오다.

테스트 코드를 작성할 때는 시나리오 ID를 주석으로 남긴다. 결과를 모을 때 어느 테스트가 어느 시나리오인지 찾기 위함이다.

```java
// TC-45: 동일 발급 건에 사용 동시 호출
@Test
void onlyOneUseSucceedsWhenCalledConcurrently() { ... }
```

### A. 정상 흐름 (End-to-End) — TC-01 ~ TC-12

| ID | 시나리오 | 실행 | 기대 결과 | 담당 |
| --- | --- | --- | --- | --- |
| TC-01 | 이벤트 생성 | `POST /admin/events` | 201, `event.status = SCHEDULED` | 전송흔 |
| TC-02 | 이벤트 이름 수정 | `PATCH /admin/events/{id}/name` | 200, 이름 변경 반영 | 전송흔 |
| TC-03 | 이벤트 설명 비우기 | `PATCH /admin/events/{id}/description` · `description: null` | 200, `description`이 NULL | 전송흔 |
| TC-04 | 이벤트 기간 수정 | `PATCH /admin/events/{id}/period` | 200, openAt·closeAt 변경 반영 | 전송흔 |
| TC-05 | 쿠폰 생성 | `POST /admin/events/{eventId}/coupons` (재고 10) | 201, `coupon_stock.remaining_quantity = 10` | 전송흔 |
| TC-06 | 이벤트 오픈 | `PATCH /admin/events/{id}/status` → OPEN | 200, `event_status_history`에 SCHEDULED→OPEN 1건 | 전송흔 |
| TC-07 | 쿠폰 발급 신청 (접수) | `POST /coupons/{couponId}/issues` | 202, result = WAITING. Redis 재고 차감, 순번 채번 | 이성집 |
| TC-08 | 비동기 확정 후 결과 조회 | 폴링 → `GET /coupon-issues/{id}/status` | 일정 시간(예: 3초) 내 ISSUED, `coupon_issue` 1건 | 이성집 |
| TC-09 | 발급 상세 조회 | `GET /coupon-issues/{id}` | 200, couponCode·expiresAt 포함, `isUsable = true` | 이성집 |
| TC-10 | 내 발급 내역 목록 | `GET /users/{userId}/coupon-issue-requests` | 200, `createdAt` 최신순 정렬 | 이성집 |
| TC-11 | 쿠폰 사용 | `POST /coupon-issues/{id}/use` | 200, USED, 이력에 ISSUED→USED 1건 | 박신형 |
| TC-12 | 사용 취소 | `POST /coupon-issues/{id}/cancel` | 200, ISSUED 복귀, 이력에 USED→ISSUED 1건 | 박신형 |

### B. 예외 흐름 — TC-20 ~ TC-38

#### 이벤트

| ID | 시나리오 | 기대 결과 | 담당 |
| --- | --- | --- | --- |
| TC-20 | openAt > closeAt로 이벤트 생성 | 400 `EVENT400-0` | 전송흔 |
| TC-21 | 존재하지 않는 이벤트 수정 | 404 `EVENT404-0` | 전송흔 |
| TC-22 | 동일 상태로 변경 (OPEN → OPEN) | 400 `EVENT400-1` | 전송흔 |
| TC-23 | 전이 순서 위반 (SCHEDULED → CLOSED) | 400 `EVENT400-2` | 전송흔 |
| TC-24 | 역방향 전이 (CLOSED → OPEN) | 400 `EVENT400-2` | 전송흔 |

#### 쿠폰 생성

| ID | 시나리오 | 기대 결과 | 담당 |
| --- | --- | --- | --- |
| TC-25 | 쿠폰 생성 불가 이벤트 상태에서 생성 | 400 `COUPON400-1` | 전송흔 |
| TC-26 | 발급 종료가 시작보다 이전 | 400 `COUPON400-2` | 전송흔 |
| TC-27 | 발급 기간이 이벤트 기간을 벗어남 | 400 `COUPON400-3` | 전송흔 |
| TC-28 | 정률 할인 정책 오류 | 400 `COUPON400-4` | 전송흔 |
| TC-29 | 정액 할인에 최대 할인 금액 설정 | 400 `COUPON400-5` | 전송흔 |

#### 발급 · 사용 · 취소

| ID | 시나리오 | 기대 결과 | 담당 |
| --- | --- | --- | --- |
| TC-30 | 존재하지 않는 쿠폰에 신청 | 404 `COUPON404-0` | 이성집 |
| TC-31 | 동일 사용자 중복 신청 | 409 `COUPON409-1`, 재고 추가 차감 없음 | 박수빈 |
| TC-32 | 재고 소진 후 신청 | 409 `COUPON409-0` | 박수빈 |
| TC-33 | 존재하지 않는 발급 건 조회 | 404 `COUPON404-1` | 이성집 |
| TC-34 | 본인 쿠폰이 아닌 건을 사용 | 403 `COUPON403-0` | 박신형 |
| TC-35 | 이미 사용한 쿠폰 재사용 | 409 `COUPON409-3` | 박신형 |
| TC-36 | 사용하지 않은 쿠폰 취소 | 409 `COUPON409-3` | 박신형 |
| TC-37 | 만료된 쿠폰 사용 | 차단 (에러코드 확인 필요) | 박신형 |
| TC-38 | 존재하지 않는 사용자의 발급 내역 조회 | 404 `USER404-0` | 이성집 |

### C. 경계 · 동시성 (핵심) — TC-40 ~ TC-46

> 선착순 시스템의 본질을 검증하는 구간이다. 초과 발급이 단 1건이라도 발생하면 실패로 판정한다.

| ID | 시나리오 | 실행 | 기대 결과 | 담당 |
| --- | --- | --- | --- | --- |
| TC-40 | 재고 1개에 동시 2명 신청 | 서로 다른 userId 2명 동시 | 성공 1건, 품절 1건. 재고 0 | 박수빈 |
| TC-41 | 재고 100에 동시 200명 신청 | 서로 다른 userId 200명 동시 | 성공 **정확히 100건**, `coupon_issue` 100건, 재고 0 | 박수빈 |
| TC-42 | 같은 사용자가 동시에 5번 신청 | 동일 userId로 동시 5요청 | 성공 1건, 나머지 `COUPON409-1` | 박수빈 |
| TC-43 | 동일 requestId 재전송 (멱등) | 같은 requestId로 2회 | 발급 1건만 생성, 재고 1만 차감, **최초 순번 반환** | 이성집 |
| TC-44 | 재고 0에 동시 50명 신청 | 소진 상태에서 동시 요청 | 전건 `COUPON409-0`, 재고 음수 없음 | 박수빈 |
| TC-45 | 동일 발급 건에 **사용** 동시 호출 | use API 동시 10요청 | 성공 1건, 이력 1건 | 박신형 |
| TC-46 | 동일 발급 건에 **취소** 동시 호출 | cancel API 동시 10요청 | 성공 1건, 이력 1건 | 박신형 |

> TC-45·TC-46은 `CouponIssueConcurrencyIntegrationTest`로 검증됨 (#59)

### D. 배치 · 정합성 — TC-50 ~ TC-66

| ID | 시나리오 | 실행 | 기대 결과 | 담당 |
| --- | --- | --- | --- | --- |
| TC-50 | 쿠폰 만료 배치 | 기한 지난 ISSUED 건 준비 후 수동 실행 | 대상만 EXPIRED, 이력에 ISSUED→EXPIRED 기록 | 박신형 |
| TC-51 | 만료 대상이 아닌 건은 미변경 | USED·기한 미도래 건 혼재 | 해당 건들 상태 유지 | 박신형 |
| TC-52 | 청크 경계 처리 | 대상 2,500건 (chunk-size 1000) | 전건 EXPIRED, 이력 2,500건. 누락 없음 | 박신형 |
| TC-53 | 배치 재실행 | TC-50 직후 다시 실행 | 대상 0건, 이력 중복 없음 | 박신형 |
| TC-54 | Redis ↔ DB 재고 정합성 | TC-41 직후 양쪽 조회 | Redis 잔여 + DB 발급 건수 = 총 재고 | 박신형 |
| TC-55 | 초기화 API 동작 | `POST /internal/coupons/{id}/reset` | 발급 이력 0건, 재고 원복, 응답의 삭제 건수와 실제 일치 | 함세연 |
| TC-56 | 초기화 API 운영 차단 | `prod` 프로파일로 기동 후 호출 | 404 (엔드포인트 미등록) | 함세연 |
| TC-57 | 정합성 검증 배치 — 정상 상태 판정 | 불일치 없는 쿠폰에 `reconcile()` 실행 | `result = MATCHED`, `errorCount = 0` | 박신형 |
| TC-58 | 정합성 검증 배치 — 이력 불일치 탐지 (HISTORY_MISMATCH) | 현재 상태와 최종 이력이 다른 건을 만든 후 실행 | `verification_detail`에 HISTORY_MISMATCH 기록 | 박신형 |
| TC-59 | 정합성 검증 배치 — 허용 안 된 전이 탐지 (INVALID_STATUS) | 화이트리스트에 없는 상태 전이 이력을 만든 후 실행 | `verification_detail`에 INVALID_STATUS 기록 | 박신형 |
| TC-60 | 정합성 검증 배치 — 중복 발급 탐지 (DUPLICATE_ISSUE) | 동일 유저·쿠폰 중복 발급 건을 만든 후 실행 | `verification_detail`에 DUPLICATE_ISSUE 기록 | 박신형 |
| TC-61 | 정합성 검증 배치 — 재현성 | 같은 `asOfAt`으로 재실행 | 이전 실행과 동일한 결과 | 박신형 |
| TC-62 | 정합성 검증 배치 — 재고 불일치 탐지 (STOCK_MISMATCH) | 재고 수량과 실제 발급 건수가 안 맞는 상황을 만든 후 실행 | `verification_detail`에 STOCK_MISMATCH 기록 | 박신형 |
| TC-63 | 정합성 검증 배치 — 중복 소비 탐지 (DUPLICATE_CONSUME) | 동일 Kafka 메시지 중복 소비 상황을 재현한 후 실행 | `verification_detail`에 DUPLICATE_CONSUME 기록 | 박신형 |
| TC-64 | 정합성 검증 배치 — 순번 결번 탐지 (SEQUENCE_GAP) | sequence_no 결번·중복 상황을 만든 후 실행 | `verification_detail`에 SEQUENCE_GAP 기록 | 박신형 |
| TC-65 | 정합성 검증 배치 — 재고 미복구 탐지 (STOCK_NOT_RESTORED) | DLQ 확정 건의 재고 보상 누락 상황을 재현한 후 실행 | `verification_detail`에 STOCK_NOT_RESTORED 기록 | 박신형 |
| TC-66 | 정합성 검증 배치 트리거 | 관리자 API로 특정 쿠폰의 `reconcile()` 수동 호출 | 즉시 실행되고 리포트가 생성됨 | 박신형 |

> TC-62 ~ TC-65는 재고/순번/DLQ 확정 로직(#58, #72)에 붙는 2단계 검증이고, TC-66은 배치를 실행시키는 트리거다. 둘 다 아직 미구현 — §7 참고.

### E. 비동기 발급 확정 (Kafka) — TC-70 ~ TC-76

> 발급 접수(API 응답)와 발급 확정(DB 저장)이 분리되어 있어, "응답이 성공"과 "실제 발급 완료"가 다른 시점이다. 이 구간이 끊기면 사용자는 성공 응답을 받았는데 쿠폰이 없는 상태가 된다.

| ID | 시나리오 | 실행 | 기대 결과 | 담당 |
| --- | --- | --- | --- | --- |
| TC-70 | 접수 → 확정 전 구간 조회 | TC-07 직후 즉시 조회 | PENDING 또는 조회 불가. 확정 전임을 구분 가능 | 정자비 |
| TC-71 | Consumer 정상 처리 | 발급 10건 접수 후 대기 | Consumer Lag 0, `coupon_issue` 10건 전건 ISSUED | 정자비 |
| TC-72 | Consumer 일시 실패 후 재시도 | DB 연결을 잠시 끊고 접수 → 복구 | 재시도 후 정상 저장. 중복 저장 없음 | 정자비 |
| TC-73 | 재시도 최종 실패 → 재고 보상 | Consumer가 계속 실패하도록 유도 | DLQ 적재 + Redis 재고 원복. 재고 누수 0 | 정자비 |
| TC-74 | Kafka 발행 자체 실패 | Kafka 중단 상태에서 신청 | 재고 즉시 보상, WAITING 응답 미발생 | 정자비 |
| TC-75 | Consumer 중복 전달 (멱등) | 동일 메시지 2회 전달 | `coupon_issue` 1건만. 유니크 위반이 500으로 새지 않음 | 정자비 |
| TC-76 | Consumer 중단 중 접수 | Consumer만 내리고 10건 접수 → 기동 | 기동 후 밀린 10건 전부 처리. 유실 0 | 정자비 |

### F. 대량 데이터 · 개인정보 — TC-80 ~ TC-85

> 소규모에서는 드러나지 않고 실제 데이터 규모에서만 나타나는 문제를 잡는 구간이다.

| ID | 시나리오 | 실행 | 기대 결과 | 담당 |
| --- | --- | --- | --- | --- |
| TC-80 | 회원 100만 건 상태에서 발급 | 더미 적재 후 TC-07 재실행 | 정상 동작, 응답 시간이 소규모 대비 유의미하게 악화되지 않음 | 함세연 |
| TC-81 | 발급 이력 300만 건 상태에서 목록 조회 | `GET /users/{userId}/coupon-issue-requests` | 정상 응답. 인덱스 사용 확인(`EXPLAIN`) | 함세연 |
| TC-82 | 발급 이력 300만 건 상태에서 만료 배치 | 배치 수동 실행 | 청크 단위로 완주, 락 대기로 발급 API가 막히지 않음 | 함세연 |
| TC-83 | 대량 상태에서 초기화 API | `POST /internal/coupons/{id}/reset` | 정상 완료, 타임아웃 없음 | 함세연 |
| TC-84 | 개인정보 마스킹 | 발급·조회 API 호출 후 로그·응답 확인 | 이메일·전화번호가 평문으로 남지 않음 | 함세연 |
| TC-85 | 발급 이력 300만 건 상태에서 정합성 검증 배치 | `reconcile()` 수동 실행 | 타임아웃 없이 완주, 청크/전수 스캔 방식에 따라 소요 시간 기록 | 박신형 |

### G. 선착순 순서 보장 — TC-90 ~ TC-94

> "몇 건 발급됐는가"와 별개로 **"먼저 온 사람이 먼저 받았는가"** 를 검증하는 구간이다.

#### 어디까지 보장되는가

| 구간 | 순서 보장 | 근거 |
| --- | --- | --- |
| 클라이언트 전송 → 서버 도착 | ❌ | 네트워크·스레드 스케줄링. 물리적으로 보장 불가 |
| 서버 도착 → Lua 호출 | ⚠️ | 대체로 유지되나 보장은 아님 |
| **Lua 내부 `INCR`** | ✅ **완전 보장** | Redis 단일 스레드 + 스크립트 원자 실행 |
| Lua → Kafka 발행 | ❌ | 비동기 |
| Kafka 파티션 간 | ❌ | 파티션 3개, 키가 `requestId`라 분산 |
| Consumer → DB 저장 | ❌ | 파티션 병렬 처리 |

**선착순 순위는 Lua의 `sequence_no`에서 확정되며, 그 이후 구간의 순서는 발급 결과에 영향을 주지 않는다.** DB 저장 순서(`created_at`)가 `sequence_no` 순서와 달라지는 것은 비동기 구조의 정상 동작이다.

따라서 순서 검증은 `created_at`이 아니라 **`sequence_no` 기준**으로 수행한다.

#### 시나리오

| ID | 시나리오 | 실행 | 기대 결과 | 담당 |
| --- | --- | --- | --- | --- |
| TC-90 | 순차 요청의 순서 일치 | 재고 100, 요청 100건을 **순차로** 전송 | 도착 순서와 `sequence_no`가 **완전 일치** (1, 2, 3 … 100) | 박수빈 |
| TC-91 | 동시 요청의 순서 역전율 | 재고 100, 동시 200건 | 서버 도착 로그 순서와 `sequence_no` 순서를 비교해 **역전 건수를 기록**. 완전 일치는 기대하지 않음 | 박수빈 |
| TC-92 | 단일 요청 전 구간 추적 | 요청 1건 후 `requestId`로 로그 검색 | 컨트롤러 → Lua → Kafka 발행 → Consumer 수신 → DB 저장까지 **모든 단계의 통과 시각이 조회됨** | 공통 |
| TC-93 | 저장 순서 역전 확인 | TC-91 직후 SQL 조회 | `created_at` 순서와 `sequence_no` 순서가 다를 수 있음. **그럼에도 `sequence_no`는 1~N 빠짐·중복 없이 부여됨** | 박수빈 |
| TC-94 | 재고 경계에서의 선착순 | 재고 100, 요청 150건 | `sequence_no` 1~100은 발급 성공, 101번째 이후 요청은 전건 `COUPON409-0` | 박수빈 |

#### 검증 쿼리

```sql
-- TC-93: sequence_no 순서와 DB 저장 순서가 다른 건수
SELECT COUNT(*) FROM (
  SELECT sequence_no,
         ROW_NUMBER() OVER (ORDER BY created_at) AS save_order
    FROM coupon_issue
   WHERE coupon_id = ?
) t
WHERE sequence_no <> save_order;
-- 0이 아니어도 정상. 비동기 저장의 특성이다.
```

```sql
-- TC-90 · TC-94: sequence_no가 1~N 빠짐·중복 없이 부여됐는지
SELECT COUNT(*)                    AS total,
       COUNT(DISTINCT sequence_no) AS distinct_no,
       MIN(sequence_no)            AS min_no,
       MAX(sequence_no)            AS max_no
  FROM coupon_issue
 WHERE coupon_id = ?;
-- 재고 100인 경우 100, 100, 1, 100 이어야 정상
```

#### 선행 조건 — 요청 도착 로그

TC-90 · TC-91 · TC-92는 **요청이 서버에 도착한 시각**을 알아야 실행할 수 있다. 현재 신청 API 경로에 로그가 없어 도착 순서를 확인할 방법이 없다.

`requestId`를 공통 키로 각 단계에 로그를 남기면, `requestId` 하나로 전 구간 흐름을 추적할 수 있다.

```
[ISSUE] 접수      requestId=... couponId=... userId=...
[ISSUE] 선점      requestId=... status=SUCCESS sequenceNo=42
[ISSUE] 발행      requestId=...
[ISSUE] 수신      requestId=...
[ISSUE] 저장 완료  requestId=... couponIssueId=...
```

| 단계 | 위치 | 현재 |
| --- | --- | --- |
| 접수 | `CouponController` / `CouponIssueServiceImpl` | 없음 |
| 선점 | `CouponIssueLuaServiceImpl` | 없음 |
| 발행 | `CouponIssueEventProducer` | 있음 |
| 수신 | `CouponIssueEventConsumer` | 있음 |
| 저장 완료 | `CouponIssuePersister` | 없음 |

#### 로그 레벨

건당 로그는 순서 검증(TC-90 ~ TC-92)에만 필요하다. 부하 테스트 규모에서는 요청 20,000건당 10만 줄이 쌓여 확인이 불가능하고, 로그 기록 시간이 응답 시간 측정에 섞인다.

레벨을 환경변수로 분리해 상황에 따라 조절한다.

```properties
logging.level.com.mycom.petcoupon.coupon=${ISSUE_LOG_LEVEL:INFO}
```

| 상황 | 설정 | 결과 |
| --- | --- | --- |
| 통합 테스트 · 순서 검증 | 기본값 (`INFO`) | 단계별 로그 전부 기록 |
| 부하 테스트 | `ISSUE_LOG_LEVEL=WARN` | 정상 경로 로그 제외, **에러 로그는 유지** |

대규모에서의 순서 검증은 로그가 아니라 SQL로 수행한다. `sequence_no`가 1~N 빠짐·중복 없이 부여됐는지만 확인하면 되기 때문이다.

## 4. 판정 기준

| 항목 | 합격 조건 |
| --- | --- |
| 기능 정확성 | 모든 시나리오의 HTTP 상태·응답 코드가 기대값과 일치 |
| 데이터 정합성 | DB 상태가 기대값과 일치. 재고 음수·초과 발급 0건 |
| 중복 방지 | 1인 1매, 동일 requestId 중복 처리 0건 |
| **선착순 순서** | `sequence_no`가 1~N 빠짐·중복 없이 부여. 순차 요청 시 도착 순서와 완전 일치 |
| 이력 기록 | 모든 상태 전이가 history 테이블에 누락 없이 기록 |
| 재현성 | 초기화 후 동일 시나리오 재실행 시 같은 결과 |

## 5. 실행 방법

| 구간 | 도구 | 비고 |
| --- | --- | --- |
| A · B (단건) | k6 또는 각 담당자의 JUnit 테스트 | 순차 실행 |
| C (동시성) | k6 소규모 스크립트 | VU를 올리면 그대로 부하 테스트 스크립트가 됨 |
| D (배치·정합성) | 수동 트리거 + SQL 조회 | 배치는 스케줄 대기 없이 직접 호출 |
| E (비동기) | 컨테이너 기동·중단 + Consumer Lag 조회 | Docker로 Kafka·Consumer를 직접 조작 |
| F (대량 데이터) | 더미 SQL 적재 후 재실행 + `EXPLAIN` | 부하 테스트 직전에 수행 |
| G (선착순 순서) | k6 + 애플리케이션 로그 + SQL 조회 | 도착 시각은 로그에서, 순위는 `sequence_no`에서 확인 |

각 담당자의 JUnit 테스트 결과는 `./gradlew test` 실행 후 `build/reports/tests/test/index.html`에서 한 번에 확인한다. 따로 결과를 취합해 전달할 필요는 없다.

## 6. 결과 기록표

| ID | 실행일 | 결과 | 실제 값 | 비고 |
| --- | --- | --- | --- | --- |
| TC-01 |  |  |  |  |
| TC-02 |  |  |  |  |
| … |  |  |  |  |

## 7. 선행 조건 (현재 미완료)

아래가 완료되어야 해당 시나리오를 실행할 수 있다.

| 항목 | 현재 상태 | 영향 범위 |
| --- | --- | --- |
| **발급 API와 큐 연결** | **미연결** — 신청 API가 아직 메모리 Mock으로 재고만 차감하고 응답한다. Redis Lua도, Kafka Producer도 호출하지 않는다 | TC-07 ~ TC-12, TC-33 ~ TC-37, TC-40 ~ TC-44, TC-50 ~ TC-55, TC-70 ~ TC-76 |
| Kafka Producer / Consumer / Recoverer | PR 검토 중 — 컴포넌트는 작성됐으나 발급 API에서 호출되지 않는다 | TC-07 ~ TC-12, TC-54, TC-70 ~ TC-76 |
| Redis 재고 초기화 로직 | 미구현 — Lua 재고 차감은 머지됐으나 재고 키를 세팅하는 주체가 없다 | TC-05, TC-54 |
| Redis 재고 보상(restore) | 미구현 | TC-73, TC-74 |
| 초기화 API의 Redis 키 삭제 | 미구현 — DB만 초기화한다. 반복 실행 시 2회차부터 전건 실패 | TC-40 ~ TC-44, TC-55 |
| 개인정보 마스킹 | 미착수 | TC-84 |
| 발급 이력 300만 더미데이터 | 미작성 | TC-81 ~ TC-83, TC-85 |
| **요청 도착 로그** | **미구현** — 신청 API 경로에 로그가 없어 도착 순서를 확인할 수 없다 | TC-90 ~ TC-92 |
| 발급 요청 큐 배치 확정 (Redis Stream 위치) | 미결정 | TC-07 응답 형태(202 WAITING vs 즉시 품절 판정)가 여기서 갈림 |
| 정합성 검증 2단계<br>- STOCK_MISMATCH<br>- DUPLICATE_CONSUME<br>- SEQUENCE_GAP<br>- STOCK_NOT_RESTORED | 미구현 | TC-62 ~ TC-65 |
| 정합성 검증 배치 트리거 (스케줄러/관리자 API) | 미구현 | TC-66 |
