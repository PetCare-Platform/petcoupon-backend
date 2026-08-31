---
title: 개발 가이드
type: how-to
audience: backend-developer
owner: petcoupon-backend-team
status: maintained
last_verified: 2026-08-28
---

# 개발 가이드

이 문서는 PetCoupon Backend 저장소에서 기능을 추가하거나 기존 코드를 변경할 때 필요한 **개발 규칙과 검증 방법**을 정리합니다.

프로젝트의 전체 기능과 실행 방법은 루트 [`README.md`](../README.md)를, 쿠폰 발급 파이프라인의 설계 이유와 동시성·정합성 전략은 [`architecture.md`](architecture.md)를, 장애 진단은 [`troubleshooting.md`](troubleshooting.md)를 참고합니다.

이 문서에서 다루는 내용은 다음과 같습니다.

- 어느 패키지에서 무엇을 수정해야 하는지
- 계층별 책임과 코딩 컨벤션
- 트랜잭션·동시성 코드를 수정할 때 지켜야 하는 규칙
- Redis, Kafka, Outbox를 변경할 때 함께 확인해야 하는 부분
- Scheduler와 Batch 개발 시 주의사항
- 테스트 작성 방법과 **변경 종류별 필수 검증 항목**
- Git·Issue·PR 컨벤션

---

## 목차

| 목적 | 항목 |
|---|---|
| 처음 환경 세팅 | [1. 개발 환경](#1-개발-환경) · [2. 주요 설정](#2-주요-설정) |
| 어디를 고쳐야 하는지 찾기 | [3. 코드 맵](#3-코드-맵) |
| 코드 작성 규칙 | [4. 코딩 컨벤션](#4-코딩-컨벤션) · [5. 계층별 개발 규칙](#5-계층별-개발-규칙) · [6. 공통 응답과 예외](#6-공통-응답과-예외-처리) |
| 위험한 영역 수정 | [7. Transaction·동시성](#7-transaction과-동시성) · [8. Redis](#8-redis-개발-규칙) · [9. Kafka·Outbox](#9-kafka와-outbox-개발-규칙) · [10. Idempotency](#10-idempotency-개발-규칙) |
| 백그라운드 작업 | [11. Scheduler](#11-scheduler-개발-규칙) · [12. Batch](#12-batch-및-reconciliation-개발-규칙) |
| 인증·내부 API | [13. 관리자 인증](#13-관리자-인증-개발-규칙) · [14. Internal API](#14-internal-api-개발-규칙) |
| 테스트 작성 | [15. 테스트 가이드](#15-테스트-가이드) ~ [19. Spring / JPA 주의사항](#19-spring--jpa-주의사항) |
| 협업 | [20. Git 컨벤션](#20-git-컨벤션) · [21. Issue 작성](#21-issue-작성) · [22. PR 작성](#22-pull-request-작성) |
| **작업 완료 전 확인** | [23. 변경 종류별 필수 확인](#23-변경-종류별-필수-확인) · [24. 핵심 불변조건](#24-변경-시-지켜야-하는-핵심-불변조건) · [25. 개발 완료 기준](#25-개발-완료-기준) |

---

## 1. 개발 환경

### 요구 환경

| 항목 | 버전 / 구성 |
| --- | --- |
| Java | 21 |
| Spring Boot | 4.1.0 |
| MySQL | 8.0 |
| Redis | 7.2 |
| Kafka | 3.7 |
| Build | Gradle Wrapper |
| Test | JUnit 5, Mockito, Awaitility, Testcontainers |

로컬 인프라는 `docker-compose.yml`을 기준으로 구성합니다. Kafka는 별도 Compose profile을 사용하므로 전체 개발 환경을 실행할 때는 다음 명령을 사용합니다.

```bash
docker compose --profile kafka up -d
```

애플리케이션은 Gradle Wrapper로 실행합니다.

```bash
./gradlew bootRun
```

### 정상 동작 확인

```bash
curl -s localhost:8080/actuator/health
# expected: {"status":"UP"}
```

의존 컨테이너 상태는 다음 명령으로 확인할 수 있습니다.

```bash
docker compose --profile kafka ps
```

### 컨테이너 정리

| 명령 | 결과 |
| --- | --- |
| `docker compose down` | 컨테이너 종료, 데이터 유지 |
| `docker compose down -v` | 볼륨까지 삭제, MySQL·Redis 데이터 초기화 |

`down -v`는 로컬 데이터 전체를 삭제하므로 **단순 재시작 용도로 사용하지 않습니다.**

### 로컬 인프라 기본 설정

`docker-compose.yml`의 기본값 중 개발·부하 테스트에 영향을 주는 항목입니다. 값을 바꾸기 전에 이유를 먼저 확인합니다.

| 항목 | 값 | 이유 |
| --- | --- | --- |
| MySQL `--max-connections` | `500` | 기본값 151은 부하 테스트에서 부족합니다. **`DB_POOL_SIZE` × 인스턴스 수의 상한**이므로 풀을 키울 때 함께 확인합니다 |
| MySQL 문자셋 | `utf8mb4` / `utf8mb4_unicode_ci` | — |
| Redis `maxmemory-policy` | `noeviction` | 쿠폰 재고 키가 메모리 압박으로 **삭제되면 재고가 남아 있어도 품절로 처리**됩니다 |
| Redis `appendonly` | `yes` | 컨테이너를 내렸다 올려도 재고 키가 남도록 AOF를 사용합니다 |
| Kafka | KRaft 단일 노드, `profiles: [kafka]` | 기본 `docker compose up -d`로는 뜨지 않습니다 |
| 컨테이너 이름 | `petcoupon-mysql` · `petcoupon-redis` · `petcoupon-kafka` | `docker exec` 명령이 이 이름을 사용합니다 |
| TZ | `Asia/Seoul` | MySQL·Kafka에 지정. **테스트의 기본 timezone은 UTC**이므로 혼동하지 않습니다 |

세 컨테이너 모두 healthcheck가 있으므로 기동 확인은 `docker compose --profile kafka ps`의 상태 컬럼을 봅니다.

---

## 2. 주요 설정

애플리케이션 설정의 기준은 [`src/main/resources/application.properties`](../src/main/resources/application.properties)입니다.

이 문서에는 개발자가 자주 변경하는 설정만 정리하고, Redis Stream·Kafka·Outbox 등의 세부 튜닝 값은 `application.properties`를 직접 확인합니다.

### 데이터베이스

| 환경변수 | 기본값 | 용도 |
| --- | --- | --- |
| `DB_URL` | `jdbc:mysql://localhost:3306/petcoupon` | MySQL URL |
| `DB_USERNAME` | `root` | MySQL 사용자 |
| `DB_PASSWORD` | `root` | MySQL 비밀번호 |
| `JPA_DDL_AUTO` | `update` | Hibernate DDL 전략 |
| `JPA_SHOW_SQL` | `false` | SQL 로그 출력 |

`spring.jpa.open-in-view=false`로 설정되어 있으므로 Controller까지 영속성 컨텍스트를 열어두는 방식에 의존하지 않습니다.

### 현재 스키마 관리 주의사항

현재 프로젝트에는 Flyway나 Liquibase 같은 **스키마 마이그레이션 도구가 적용되어 있지 않습니다.** 개발 환경 기본값은 다음과 같습니다.

```properties
spring.jpa.hibernate.ddl-auto=update
```

일부 Index와 Unique Constraint는 Entity의 `@Index`, `@UniqueConstraint`를 기반으로 Hibernate가 생성합니다. 따라서 `JPA_DDL_AUTO=validate`로 **단순 변경해서는 안 됩니다.**

`validate`로 전환하려면 먼저 현재 Entity에 정의된 다음 요소들을 실제 SQL migration으로 옮겨야 합니다.

- Index
- Unique Constraint
- Table / Column 변경
- 성능에 필요한 복합 Index

특히 Index가 빠진 경우 **애플리케이션이 즉시 실패하지 않고 쿼리 성능만 저하**되므로 알아채기 어렵습니다. 대상 Index 목록은 `application.properties`의 `ddl-auto` 주석에 정리되어 있습니다.

### Redis

| 환경변수 | 기본값 |
| --- | --- |
| `REDIS_HOST` | `localhost` |
| `REDIS_PORT` | `6379` |

쿠폰 발급과 관련된 Redis key는 임의의 문자열로 새로 작성하지 않고 `CouponIssueRedisKeys` 등 **기존 key 정의를 우선 사용합니다.**

### Kafka

| 환경변수 | 기본값 |
| --- | --- |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `KAFKA_CONSUMER_GROUP_ID` | `petcoupon` |
| `KAFKA_DLQ_CONSUMER_GROUP_ID` | `petcoupon-dlq` |

메인 Consumer와 DLQ Consumer는 서로 다른 Consumer Group을 사용합니다.

Kafka Producer의 retry 시간만 임의로 크게 늘리지 않습니다. 이 프로젝트에서는 발급 메시지의 재시도와 최종 DLQ 전환을 **Outbox 계층에서도 관리**하므로, Kafka Client의 장시간 retry와 Outbox retry가 중첩되지 않도록 설정되어 있습니다.

### Scheduler

상태 전이 및 정합성 검증 Scheduler는 환경변수로 비활성화할 수 있습니다.

| 환경변수 | 기본값 |
| --- | --- |
| `EVENT_STATUS_SCHEDULER_ENABLED` | `true` |
| `COUPON_STATUS_SCHEDULER_ENABLED` | `true` |
| `COUPON_RECONCILIATION_SCHEDULER_ENABLED` | `true` |

테스트 및 부하 테스트에서는 필요한 Scheduler만 명시적으로 켜고, 테스트 데이터에 개입할 수 있는 Scheduler는 끄는 것을 원칙으로 합니다.

### 관리자 인증

| 환경변수 | 기본값 |
| --- | --- |
| `ADMIN_AUTH_CODE` | `local-dev-admin-auth-code` |
| `ADMIN_SESSION_TTL` | `PT8H` |

`local-dev-admin-auth-code`는 로컬 개발 편의를 위한 기본값입니다. **실제 배포 환경에서는 반드시 별도의 `ADMIN_AUTH_CODE`를 설정해야 합니다.**

### 로그

| 환경변수 | 용도 |
| --- | --- |
| `ISSUE_LOG_LEVEL` | 발급 파이프라인 로그 레벨. 부하 테스트 시 `WARN` 등으로 낮춤 |
| `LOG_FILE` | 파일 로그 경로. 미지정 시 콘솔만 출력 |

---

## 3. 코드 맵

기본 package는 `src/main/java/com/mycom/petcoupon/` 입니다.

| Package | 책임 |
| --- | --- |
| `coupon` | 쿠폰 생성·수정·조회, 쿠폰 상태, 사용·취소·만료 |
| `coupon.issue` | Redis Stream, Lua, Kafka를 이용한 비동기 쿠폰 발급 |
| `event` | 이벤트 생성·수정·조회 및 상태 관리 |
| `idempotency` | 발급 요청 Idempotency-Key 처리 |
| `messaging` | 발급 Outbox 메시지 저장 및 Kafka 발행 |
| `reconciliation` | Spring Batch 기반 발급 정합성 검증 |
| `notification` | 발급 결과 알림 기록 |
| `dashboard` | 관리자 대시보드 집계 |
| `monitoring` | WARN/ERROR 로그 수집 및 SSE 스트림 |
| `system` | MySQL·Redis 등 시스템 상태 확인 |
| `internal` | 부하 테스트 지원용 내부 API |
| `user` | 사용자 Entity 및 조회 |
| `global` | 공통 응답, 예외 처리, 인증, 공통 설정 |

일반적인 CRUD 성격의 도메인은 다음 구조를 기본으로 합니다.

```text
domain/
├── controller/
├── service/
├── repository/
├── entity/
├── dto/
│   ├── req/
│   └── res/
├── converter/
└── exception/
```

다만 **모든 기능을 이 구조에 억지로 맞추지는 않습니다.** 예를 들어 `coupon.issue`는 비동기 처리의 기술적 책임을 기준으로 분리되어 있습니다.

```text
coupon/issue/
├── config/
├── producer/
├── consumer/
├── service/
└── dto/
```

`reconciliation` 역시 Batch Job, Tasklet, Chunk Processor 등 Spring Batch의 실행 구조를 기준으로 package를 나눕니다.

새 기능을 추가할 때는 새로운 구조를 만들기 전에 **동일한 책임을 가진 기존 package의 구조를 우선 확인합니다.**

---

## 4. 코딩 컨벤션

### 네이밍

| 대상 | 규칙 | 예시 |
| --- | --- | --- |
| Class | PascalCase | `CouponService`, `CouponController` |
| Method | camelCase (동사 시작) | `issueCoupon()`, `createCoupon()` |
| Variable | camelCase | `userId`, `couponCount` |
| Constant | UPPER_SNAKE_CASE | `MAX_COUPON_COUNT` |
| Package | 소문자 | `coupon`, `issue`, `user`, `global` |
| JoinColumn | `참조테이블명_id` | `coupon_id` |

### 코드 스타일

- **Indent** — 4 Spaces
- **Line Length** — 120자 이하
- **Format** — IntelliJ Code Format 사용
- **Method** — 하나의 메서드는 하나의 역할만 수행

### Entity

- **Setter를 두지 않습니다.** 상태 변경은 의도가 드러나는 메서드로 만듭니다
- 연관관계는 `FetchType.LAZY`가 기본입니다
- Entity와 DTO 모두 `@Builder`를 사용합니다
- **Entity를 직접 반환하지 않습니다.** DTO로 변환해서 내보냅니다

### DI

생성자 주입(`@RequiredArgsConstructor`)을 사용합니다.

### 주석

위치는 어노테이션 위·코드 위·코드 옆 중 자유롭게 씁니다.

이 저장소는 "무엇을"보다 **"왜"** 를 남기는 관행을 따릅니다. 특히 다음은 근거를 적습니다.

- 트랜잭션 경계를 나눈 이유
- 락을 잡은 이유와 **잠그는 순서**
- 기본 동작 대신 다른 선택을 한 이유
- 예외를 삼키거나 재전파하는 이유

---

## 5. 계층별 개발 규칙

계층은 `Controller → Service → Repository` 순으로만 의존합니다.

```text
HTTP Request
    ↓
Controller
    ↓
Service
    ↓
Repository / Infrastructure
```

### Controller

Controller는 HTTP 요청과 응답 처리에 집중합니다.

- Request Body / Path Variable / Query Parameter 수신
- Bean Validation
- Service 호출
- `CustomResponse` 생성
- 적절한 HTTP Status 반환

**비즈니스 규칙이나 데이터 접근 로직을 Controller에 직접 구현하지 않습니다.** Controller에서 Repository, RedisTemplate, KafkaTemplate 등을 직접 호출하는 방식은 기존 구조와 맞지 않습니다.

Request Body 검증에는 `@Valid`를 사용합니다.

```java
@PostMapping
public CustomResponse<EventCreateResponse> create(
        @Valid @RequestBody EventCreateRequest request
) {
    return CustomResponse.onSuccess(HttpStatus.CREATED, eventService.createEvent(request));
}
```

`@PathVariable`, `@RequestParam`에 `@Positive`, `@Min` 등의 Validation을 적용하려면 **Controller 클래스에 `@Validated`가 필요합니다.**

```java
@Validated
@RestController
public class ExampleController {
}
```

### API 반환 타입

**기본적으로 모든 API는 `CustomResponse<T>`를 반환하고 `ResponseEntity`는 사용하지 않습니다.**

현재 코드에서 `ResponseEntity`를 쓰는 곳은 세 군데뿐이며, 모두 `CustomResponse`로 표현할 수 없는 이유가 있습니다.

| 위치 | 이유 |
| --- | --- |
| `CouponController.issue` | 발급 접수 `202`와 멱등 재현 응답에서 상태·본문을 직접 지정해야 함 |
| `CouponIssueController.getCouponIssueRequestStatus` | 신청 시점에 저장해둔 응답 JSON과 HTTP 상태를 **그대로 재현**해야 함 |
| `MonitoringController.stream` | SSE 응답에 버퍼링·캐시 헤더를 명시해야 함 |

새 API를 추가할 때 이 목록에 해당하지 않는다면 `CustomResponse`를 반환합니다.

### DTO

Request와 Response DTO는 분리하고, Java `record`로 작성합니다.

```text
event/dto/
├── req/
│   └── EventCreateRequest.java
└── res/
    └── EventCreateResponse.java
```

이름은 **도메인 + 행위 + Request / Response** 형식입니다. (`CouponCreateRequest`, `EventCreateResponse`)

입력값 자체의 형식 검증은 DTO의 Bean Validation으로 처리합니다. (`@NotNull`, `@NotBlank`, `@Size`, `@Positive`)

다만 **다른 데이터와 비교해야 하는 검증**이나 **현재 상태에 따라 결과가 달라지는 검증**은 Service에서 처리합니다. 다음은 DTO Validation의 책임이 아닙니다.

- 이벤트 시작 시간이 종료 시간보다 빠른지
- 현재 쿠폰 상태에서 수정 가능한지
- 이벤트가 쿠폰 생성 가능한 상태인지
- 사용자가 이미 쿠폰을 발급받았는지

### Converter

Entity와 DTO 사이의 변환은 `converter` package에서 처리합니다.

```java
// event/converter/EventConverter.java
@Component
public class EventConverter {

    public EventCreateResponse toCreateResponse(Event event) {
        return new EventCreateResponse(
                event.getEventId(),
                event.getName(),
                event.getStatus()
        );
    }
}
```

Controller 또는 Service 내부에 동일한 DTO 조립 코드가 반복되면 기존 Converter에 책임을 모읍니다.

Converter는 별도의 상태를 갖지 않는 변환 로직을 중심으로 구성하며, 주요 변환 로직은 **독립된 단위 테스트로 검증합니다.**

### Service

Service는 다음 책임을 가집니다.

- 비즈니스 규칙
- 상태 전이 검증
- Transaction 경계
- Repository 조합
- 외부 저장소 또는 Messaging 조합
- 도메인 예외 발생

데이터를 변경하는 주요 Service 메서드는 명시적으로 Transaction 경계를 설정하고, 조회만 수행하는 메서드는 가능한 경우 read-only Transaction을 사용합니다.

```java
@Transactional
public void update(...) { ... }

@Transactional(readOnly = true)
public ExampleResponse get(...) { ... }
```

비즈니스 오류는 임의의 `RuntimeException`이나 HTTP 예외를 직접 발생시키지 않고, **기존 도메인의 `ErrorCode`와 `GeneralException` 구조를 사용합니다.**

### Repository

Repository는 데이터 조회와 DB 단위의 원자적 변경을 담당합니다. 단순 CRUD뿐 아니라 동시성 제어가 필요한 부분에서는 다음 패턴도 사용합니다.

- Pessimistic Lock
- Conditional UPDATE
- `@Modifying` Query
- Bulk UPDATE
- DB 기준 시간 조회

상태를 읽은 다음 Java에서 판단하고 다시 저장하는 과정에 경쟁 조건이 생길 수 있다면 **기존 Conditional UPDATE 방식을 우선 검토합니다.**

```sql
UPDATE ...
SET status = ?
WHERE id = ?
  AND status = ?
```

변경 건수가 `0`인지 `1`인지 자체가 동시성 제어 결과가 되는 경우, **Service에서 결과 건수를 반드시 확인합니다.**

---

## 6. 공통 응답과 예외 처리

모든 일반 API 응답은 `CustomResponse`를 사용합니다.

```json
{
  "isSuccess": true,
  "code": "200",
  "message": "OK",
  "result": {}
}
```

주요 메서드는 다음과 같습니다.

```java
CustomResponse.onSuccess(result);                     // 200
CustomResponse.onSuccess(HttpStatus.CREATED, result); // 상태 지정
CustomResponse.onFailure(errorCode);                  // 에러 코드 기반
CustomResponse.onFailure(code, message);              // 직접 지정
```

실제 사용 가능한 메서드는 [`global/common/CustomResponse`](../src/main/java/com/mycom/petcoupon/global/common/CustomResponse.java)를 기준으로 합니다.

### ErrorCode

도메인별 예외는 `BaseErrorCode`를 구현하는 ErrorCode enum을 사용합니다.

- **위치** — `{domain}/exception/{Domain}ErrorCode.java`
- **코드 체계** — `{DOMAIN}{HTTP_STATUS}-{순번}` (0-base)

```java
@Getter
@AllArgsConstructor
public enum CouponErrorCode implements BaseErrorCode {

    INVALID_ISSUE_REQUEST(HttpStatus.BAD_REQUEST, "COUPON400-0", "쿠폰 신청 요청값이 올바르지 않습니다."),
    COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "COUPON404-0", "존재하지 않는 쿠폰입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
```

새로운 ErrorCode를 추가할 때는 **동일한 도메인과 HTTP Status의 기존 번호를 먼저 검색합니다.**

> 여러 PR이 병렬로 진행되면서 같은 ErrorCode 번호가 충돌한 이력이 있으므로, 기존 sequence를 확인하지 않고 번호를 임의로 추가하지 않습니다.

### GlobalExceptionHandler

모든 예외 응답은 `@RestControllerAdvice`인 `GlobalExceptionHandler`를 통해 처리합니다. 현재 전역 Handler는 다음을 공통 처리합니다.

- `GeneralException`
- `@Valid` 실패
- `@PathVariable` / `@RequestParam` Validation 실패
- 필수 Header 누락
- Enum 및 타입 변환 실패
- 잘못된 JSON
- 지원하지 않는 HTTP Method
- 존재하지 않는 Endpoint (`NoResourceFoundException`)
- 처리되지 않은 서버 예외

**개별 Controller에서 같은 오류 응답 구조를 다시 만들지 않습니다.** `try-catch`는 필요한 경우에만 사용합니다.

### SSE와 오류 Content-Type

전역 오류 응답은 명시적으로 `application/json`을 설정합니다.

이는 `/admin/monitoring/stream`처럼 클라이언트가 `Accept: text/event-stream`만 전달하는 경우에도 인증 실패나 잘못된 요청에 대한 JSON 오류를 안정적으로 반환하기 위함입니다.

전역 Error Handler의 Content-Type 처리를 제거하거나 일반 Content Negotiation에 맡기면 SSE 요청의 원래 401/404/405 오류가 다른 오류로 변할 수 있으므로 **변경 시 관련 테스트를 반드시 확인합니다.**

---

## 7. Transaction과 동시성

PetCoupon에서 Transaction과 동시성 제어는 단순 구현 세부사항이 아니라 **시스템의 핵심 계약**입니다.

관련 코드를 수정할 때는 현재 테스트가 통과하는 것뿐 아니라 다음 불변조건이 유지되는지 확인합니다.

### Transactional self-invocation을 사용하지 않는다

Spring의 `@Transactional`은 Proxy를 통해 적용됩니다. 동일 Bean 내부에서 Transaction 메서드를 직접 호출하면 Proxy를 거치지 않아 Transaction이 기대대로 적용되지 않을 수 있습니다.

쿠폰 발급 Kafka Consumer 처리에서는 이 문제를 피하기 위해 DB 반영 로직을 `CouponIssuePersister`라는 **별도 Spring Bean으로 분리**합니다.

따라서 Transaction 경계를 추가할 때 "같은 클래스 내부의 메서드에 `@Transactional` 추가"만으로 해결하려 하지 않습니다. Transaction 자체가 독립적인 의미를 가져야 한다면 별도 Bean으로 분리하는 현재 구조를 따릅니다.

### 쿠폰 관리자 수정의 Lock 순서를 유지한다

관리자 쿠폰 수정에서는 Pessimistic Lock 획득 순서가 정해져 있습니다.

```text
Coupon → CouponStock
```

**관련 코드에서 Lock 순서를 반대로 가져가지 않습니다.** 동일 자원을 서로 다른 순서로 Lock하면 동시 요청에서 Deadlock 가능성이 증가합니다.

쿠폰과 재고를 동시에 잠그는 새로운 경로가 추가된다면 기존과 동일한 순서를 사용합니다.

### Conditional UPDATE를 단순 find-save로 바꾸지 않는다

쿠폰 상태, 발급 상태, 사용·취소·만료 등 일부 상태 변경은 DB Conditional UPDATE를 사용합니다. 이는 다음 사이에 다른 Transaction이 상태를 변경하는 **TOCTOU 문제**를 줄이기 위한 것입니다.

```text
조회 → 애플리케이션에서 상태 확인 → UPDATE
```

기존 Conditional UPDATE를 Entity 조회 후 `save()` 방식으로 단순화할 때는 동시성 보장이 달라지지 않는지 먼저 검증해야 합니다.

### Unique Constraint는 최종 방어선이다

애플리케이션의 사전 조회만으로 중복을 완전히 막지 않습니다. 쿠폰 발급 파이프라인에서는 **DB Unique Constraint도 최종 정합성 방어선**으로 사용합니다.

- 동일 쿠폰의 사용자 중복 발급
- 쿠폰별 발급 순번 중복
- 동일 발급 요청의 중복 반영

Kafka는 메시지를 다시 전달할 수 있으므로 Consumer 코드는 **중복 전달을 정상적인 상황으로 가정해야 합니다.**

---

## 8. Redis 개발 규칙

Redis는 단순 Cache가 아니라 **쿠폰 발급 과정의 실시간 상태 저장소**로 사용됩니다. 따라서 Redis 관련 변경은 일반 Cache 변경보다 영향 범위가 큽니다.

### Lua의 원자성을 유지한다

현재 Lua Script는 서로 분리되면 안 되는 여러 연산을 하나의 원자적 작업으로 처리합니다.

- 재고 확인
- 재고 차감
- 사용자 중복 신청 확인
- 발급 순번 관리

이를 다음과 같이 여러 Redis 명령으로 분리하면 안 됩니다.

```text
GET stock → Java에서 검사 → DECR stock → applicant 확인
```

여러 요청이 동시에 들어오는 환경에서는 **명령 사이에 다른 요청이 개입**할 수 있습니다.

Lua Script의 반환 코드나 Redis key 구조를 변경하는 경우 다음 영역도 함께 확인합니다.

- `CouponIssueLuaService`
- Lua result enum / DTO
- 재고 복구 Lua
- Pending / DLQ 처리
- Internal Reset
- Lua Integration Test
- Concurrency Test

### Redis key를 여러 위치에 하드코딩하지 않는다

발급 관련 key는 기존 key 정의 클래스(`CouponIssueRedisKeys`) 또는 설정을 사용합니다.

새 key가 필요한 경우 Producer, Consumer, Reset, Recovery가 각각 다른 문자열을 갖지 않도록 **하나의 기준**을 둡니다.

### DB와 Redis를 하나의 Transaction으로 가정하지 않는다

MySQL Transaction이 rollback되더라도 **Redis 명령은 자동 rollback되지 않습니다.** 반대로 Redis 변경이 실패하더라도 이미 commit된 MySQL Transaction은 자동으로 되돌아가지 않습니다.

DB와 Redis를 함께 변경하는 기능에서는 반드시 다음을 결정해야 합니다.

```text
1. 어느 저장소를 먼저 변경하는가
2. 중간 단계에서 실패하면 어떤 상태가 남는가
3. 재시도 가능한가
4. 복구 시 어느 데이터를 기준으로 하는가
```

쿠폰 발급과 관리자 수정, Reset 기능은 이 차이를 명시적으로 처리하고 있으므로, 단순히 하나의 `@Transactional` 안에 코드를 넣었다는 이유로 **전체 작업이 원자적이라고 가정하지 않습니다.**

---

## 9. Kafka와 Outbox 개발 규칙

쿠폰 발급의 비동기 처리에서는 Kafka의 **at-least-once** 전달 가능성을 전제로 합니다. 즉 다음은 비정상이 아니라 정상적으로 발생 가능한 상황입니다.

```text
같은 메시지가 Consumer에 두 번 이상 전달됨
```

### DB 발급 확정은 하나의 Transaction으로 처리한다

`CouponIssuePersister`에서는 발급 확정에 필요한 주요 MySQL 변경을 같은 Transaction으로 묶습니다.

- `coupon_issue`
- `coupon_stock`
- `coupon_issue_history`
- Idempotency 최종 상태
- Outbox 메시지 상태

이 경계를 임의로 여러 Transaction으로 쪼갤 경우 **일부 데이터만 commit되는 상태**가 생기지 않는지 확인해야 합니다.

### 알림 실패가 발급 Transaction을 rollback시키지 않는다

알림은 발급 성공 여부와 동일한 핵심 Transaction으로 취급하지 않습니다. 쿠폰 발급 자체는 성공했는데 Notification 저장 또는 전송이 실패했다는 이유로 **실제 발급이 rollback되는 구조를 만들지 않습니다.**

### Outbox와 Kafka retry를 중복으로 과도하게 늘리지 않는다

현재 Outbox Publisher가 자체적인 retry 및 DLQ 정책을 가집니다. 따라서 Kafka Producer의 timeout 또는 retry를 변경할 때는 두 계층의 **전체 지연 시간을 같이 계산**해야 합니다.

```text
Kafka Client retry + Outbox retry
```

한 계층의 안정성을 높이기 위해 timeout을 크게 늘렸다가 전체 장애 감지와 DLQ 전환이 수 분 이상 지연되지 않도록 합니다.

### DLQ Consumer는 메인 Consumer와 분리한다

현재 DLQ Consumer는 메인 발급 Consumer와 별도 Consumer Group을 사용합니다. 운영·모니터링·확장 단위를 분리하기 위한 구조이므로 **특별한 이유 없이 같은 group으로 합치지 않습니다.**

---

## 10. Idempotency 개발 규칙

쿠폰 발급 API는 `Idempotency-Key`를 이용해 동일 요청의 중복 처리를 방지합니다.

Idempotency는 단순히 key 존재 여부만 확인하는 기능이 아닙니다. 현재 구현은 **요청 상태와 최종 처리 결과를 관리**하며 동시 요청과 재전송을 고려합니다.

### Key 생성과 후속 처리의 Transaction 경계를 확인한다

Idempotency Key 생성은 이후 발급 로직 전체와 **항상 같은 Transaction으로 묶이지 않습니다.** 후속 처리에서 오류가 발생해도 이미 받은 요청의 흔적과 상태를 적절히 남길 수 있도록 Transaction 경계를 분리한 코드가 존재합니다.

관련 코드를 리팩터링하면서 모든 처리를 하나의 Transaction으로 합칠 경우 다음 상황을 확인합니다.

```text
요청 수신
→ Idempotency Key 생성
→ 이후 처리 실패
→ 전체 Transaction rollback
→ 서버가 해당 요청을 처음 본 것처럼 됨
```

이런 상태가 의도한 동작인지 검증해야 합니다.

### 상태 변경은 무조건 덮어쓰지 않는다

Idempotency 상태 변경은 동시 요청을 고려해야 합니다. 현재 상태를 확인하지 않고 무조건 `SUCCESS` / `FAILED`로 덮어쓰는 방식보다 **조건부 상태 변경**을 유지합니다.

### Key 재사용 시 요청 의미가 같은지 확인한다

같은 Idempotency-Key가 다른 요청 내용에 재사용되는 상황도 고려합니다. Key가 같다는 이유만으로 전혀 다른 요청을 동일 요청으로 취급하지 않도록 기존 request 정보 및 fingerprint 처리 방식을 확인합니다.

---

## 11. Scheduler 개발 규칙

상태 전이 Scheduler는 Background Task이므로 일반 Service 코드와 다른 문제가 발생할 수 있습니다.

### Scheduler 실행과 비즈니스 Transaction을 구분한다

Coupon Status Scheduler는 실행 등록과 실제 상태 변경 Service의 책임을 분리합니다.

**Transaction이 적용된 Service 내부에서 모든 Exception을 잡아 삼키지 않습니다.** DB Transaction 과정에서 오류가 발생하면 해당 실행을 정상적으로 rollback시키고, Scheduler 실행 계층에서 오류를 처리하여 다음 주기에 다시 시도할 수 있도록 합니다.

Transaction 내부에서 Exception을 무조건 catch하면 다음 문제가 발생할 수 있습니다.

```text
DB 작업 실패
→ Transaction rollback-only
→ 내부에서 Exception 무시
→ commit 시점에 다시 Transaction Exception 발생
```

### 상태 전이 순서를 임의로 바꾸지 않는다

Coupon 상태 Scheduler에는 **의도된 실행 순서**가 있습니다.

```text
READY → ACTIVE
ACTIVE → SOLD_OUT
ACTIVE / SOLD_OUT → ENDED
```

같은 Scheduler 실행에서 어떤 상태까지 진행할 수 있는지가 호출 순서에 영향을 받을 수 있습니다. 따라서 **메서드 순서를 단순 정리 목적으로 변경하지 않습니다.**

### 재시도 가능한 UPDATE를 유지한다

Scheduler 상태 전이는 Conditional UPDATE를 사용하여 이미 처리된 데이터를 다시 실행하더라도 안전하게 동작하도록 설계된 부분이 있습니다.

Scheduler 코드를 변경할 때 **같은 작업이 다음 주기에 다시 실행되어도 결과가 깨지지 않는지** 확인합니다.

### 전용 스레드 풀

`TaskScheduler` 빈이 여러 개이므로 스케줄러마다 전용 스레드 풀을 명시합니다. 공유 레지스트라를 쓰면 다른 작업까지 같은 풀로 끌려갑니다.

---

## 12. Batch 및 Reconciliation 개발 규칙

정합성 검증은 Spring Batch를 사용하며, 관련 코드는 다음 package에서 관리합니다.

```text
reconciliation/
├── batch/
│   ├── chunk/
│   ├── config/
│   ├── service/
│   └── tasklet/
├── scheduler/
├── service/
└── repository/
```

Batch 변경 시 단순 Job 성공 여부뿐 아니라 다음을 검증합니다.

- Job 재실행 가능 여부
- 중간 실패 후 Restart
- Job Repository 상태
- DataSource 연결
- 같은 Coupon에 대한 중복 실행
- Scheduler에 의한 자동 실행
- 대량 데이터 처리 시 쿼리 범위

Reconciliation Scheduler는 `ENDED` Coupon을 대상으로 **30분 주기**로 정합성 검증을 수행합니다. 발급 데이터가 많은 환경에서는 실행 비용이 커질 수 있으므로 부하 테스트 중에는 다음 환경변수로 비활성화합니다.

```text
COUPON_RECONCILIATION_SCHEDULER_ENABLED=false
```

---

## 13. 관리자 인증 개발 규칙

`/admin/**`는 `AdminSessionInterceptor`의 인증 대상이며, 일반 관리자 API는 `X-ADMIN-KEY` Header가 필요합니다.

**관리자 인증을 새 Controller마다 직접 구현하지 않습니다.** `WebConfig`에 등록된 Interceptor가 `/admin/**` 전체에 적용됩니다.

세션 생성과 같이 인증 이전에 호출되어야 하는 Endpoint만 `@NoAdminSession`으로 명시적인 예외 처리를 사용합니다. 경로 단위(`excludePathPatterns`)로 빼지 않는 이유는, 같은 경로의 다른 메서드(예: 세션 폐기 `DELETE`)까지 함께 열리기 때문입니다.

새로운 관리자 Endpoint를 추가할 때 `/admin/**` 경로에 포함된다면 **기본적으로 인증된 Endpoint라고 가정합니다.** 인증이 필요하지 않다면 "편의상 제외"하지 말고 실제로 인증 예외가 필요한 Endpoint인지 먼저 확인합니다.

---

## 14. Internal API 개발 규칙

`internal` package는 부하 테스트 등 내부 도구를 위한 기능을 관리하며 `prod` 프로파일에서는 비활성화됩니다. **일반 사용자 API와 동일한 공개 API로 취급하지 않습니다.**

특히 Coupon Reset은 단순 DELETE API가 아닙니다.

### Pipeline이 비워진 뒤 Reset한다

발급 처리 중인 메시지가 남은 상태에서 DB와 Redis를 초기화하면 다음 문제가 발생할 수 있습니다.

```text
Reset
    ↓
DB 데이터 삭제 / Redis 초기화
    ↓
Reset 전에 들어온 Kafka / Stream 메시지가 뒤늦게 처리
    ↓
초기화한 DB에 발급 데이터가 다시 생성됨
```

이를 방지하기 위해 Reset 전에 전체 발급 Pipeline이 drain된 상태인지 확인합니다. **Reset 조건을 완화하거나 제거할 때는 위와 같은 late message 처리까지 검증해야 합니다.**

### DB 삭제는 FK 순서를 지킨다

Bulk Delete를 추가할 때 FK 관계를 확인하고 **자식 데이터부터** 삭제합니다. 테스트 cleanup도 같은 원칙을 적용합니다.

### Bulk DML 이후 Persistence Context를 주의한다

JPQL `@Modifying` 또는 Bulk SQL은 현재 Persistence Context에 올라간 Entity 상태를 **자동 갱신하지 않습니다.** Bulk 변경 후 기존 Entity Reference를 계속 사용할 경우 stale state가 남을 수 있으므로 필요하면 `flush` / `clear` / 재조회를 검토합니다.

### Bulk UPDATE는 JPA Auditing을 거치지 않는다

Bulk UPDATE는 일반 Entity 변경과 달리 JPA Entity Lifecycle Callback을 거치지 않습니다. 따라서 `updatedAt`과 같은 Auditing 필드가 필요한 Query는 **SQL/JPQL에서 직접 갱신해야 할 수 있습니다.**

### Redis 초기화 후 발급 상태를 다시 생성한다

Redis 발급 상태를 삭제만 하고 끝내면 Lua Script가 **재고가 초기화되지 않은 상태로 판단**할 수 있습니다.

Reset에서는 Redis 상태 삭제 이후 DB의 쿠폰 수량을 기준으로 실시간 발급 재고를 다시 초기화하는 과정까지 하나의 작업으로 봅니다.

---

## 15. 테스트 가이드

전체 테스트는 다음 명령으로 실행합니다.

```bash
./gradlew test
```

통합 테스트가 **실제 MySQL·Redis·Kafka에 붙으므로** 실행 전에 의존 서비스를 띄워야 합니다.

```bash
docker compose --profile kafka up -d
```

단위 테스트와 통합 테스트는 **둘 다 작성하는 것을 원칙**으로 합니다.

### Controller Test

HTTP 계약을 검증합니다.

- HTTP Status
- Request Validation
- `CustomResponse`
- ErrorCode
- Path / Query Parameter
- 관리자 인증
- GlobalExceptionHandler

일부 Controller Test에서는 Spring 전체 Context를 띄우지 않고 `MockMvcBuilders.standaloneSetup(...)`과 Mock Service를 사용합니다. **Controller 로직 검증을 위해 불필요하게 MySQL, Redis, Kafka까지 기동하지 않습니다.**

> `standaloneSetup`은 AOP 프록시를 만들지 않으므로 `@Validated` 기반 메서드 파라미터 검증이 실제로는 동작하지 않습니다. 이 경우 Controller에서 직접 검증하는 코드가 있는지 확인합니다.

### Service Unit Test

Service의 비즈니스 규칙은 Mockito 기반 단위 테스트로 검증합니다.

- 입력 상태별 성공 / 실패
- Repository 호출 조건
- 상태 전이
- ErrorCode
- Lock 획득 경로
- Conditional UPDATE 결과 처리

### Repository Test

DB Query 자체가 중요할 경우 실제 JPA 동작을 검증합니다. 특히 다음 Query는 Repository 수준 검증 가치가 높습니다.

- Lock Query
- Conditional UPDATE
- Pagination
- DB Time
- Bulk UPDATE
- 집계 Query

### Integration Test

실제 컴포넌트 간 연결이 중요한 기능은 `@SpringBootTest` 기반 통합 테스트를 사용합니다.

Redis Lua · Redis Stream · Kafka · Outbox · 비동기 발급 Pipeline · Idempotency · Redis/DB 상태 동기화 · Scheduler · Reconciliation Batch

### Concurrency Test

동시성 요구사항은 단위 테스트만으로 검증하지 않습니다. 다음 조건은 **실제 동시 요청**으로 확인합니다.

- 초과 발급이 발생하지 않는가
- 같은 사용자가 중복 발급되지 않는가
- 발급 순번이 중복되지 않는가
- 동일 Idempotency 요청이 중복 완료되지 않는가
- Kafka 중복 전달이 DB 중복 데이터로 이어지지 않는가

### 시나리오 ID 주석

테스트 코드에는 통합 테스트 시나리오 ID를 주석으로 남깁니다.

```java
// TC-45: 동일 발급 건에 사용 동시 호출
@Test
void onlyOneUseSucceedsWhenCalledConcurrently() { ... }
```

시나리오 전체는 [`../load-test/docs/integration-test-scenario.md`](../load-test/docs/integration-test-scenario.md)에 있습니다.

---

## 16. 비동기 테스트 작성 규칙

Redis Stream, Kafka, Outbox 등은 요청 직후 결과가 만들어진다는 보장이 없습니다. 따라서 비동기 테스트에서 **고정된 `Thread.sleep()`으로 완료 시점을 추측하지 않습니다.**

현재 프로젝트는 Awaitility를 사용합니다.

```java
await()
    .atMost(...)
    .untilAsserted(() -> {
        // 최종 상태 확인
    });
```

검증 대상은 중간 구현 세부사항보다 **최종 계약**을 우선합니다.

```text
API 202 → Stream → Lua → Outbox → Kafka → DB

최종 검증:
coupon_issue 존재
coupon_stock 일치
history 존재
idempotency 최종 상태 일치
```

---

## 17. `@SpringBootTest`와 Scheduler

**이 프로젝트의 통합 테스트에서 가장 주의해야 하는 부분입니다.**

Full Application Context를 띄우면 실제 Scheduler도 같이 실행될 수 있습니다. 과거 통합 테스트에서는 테스트 도중 Event Status Scheduler가 공유 DB 상태를 변경하여 예상하지 않은 `event_status_history`를 만들고, 이후 cleanup 과정에서 FK 오류가 발생한 사례가 있었습니다.

Spring은 테스트 컨텍스트를 캐시하고 클래스가 끝나도 닫지 않으므로, **스케줄러가 살아 있는 컨텍스트가 하나라도 있으면 빌드가 끝날 때까지 다른 테스트의 데이터를 건드립니다.**

따라서 Scheduler 자체를 검증하는 테스트가 아니라면 필요한 Scheduler를 명시적으로 비활성화합니다.

```java
@SpringBootTest(properties = {
    "event.status.scheduler.enabled=false",
    "coupon.status.enabled=false",
    "coupon.reconciliation.scheduler.enabled=false"
})
class ExampleIntegrationTest {
}
```

**모든 테스트에서 무조건 세 개를 끌 필요는 없습니다.** 테스트 대상이 아닌 백그라운드 작업만 비활성화하고, Scheduler 동작 자체를 검증하는 테스트에서는 해당 Scheduler를 활성화해야 합니다.

### Cron을 길게 변경해서 해결하지 않는다

다음 방식은 테스트 격리 방법으로 사용하지 않습니다.

> "테스트 중에 안 돌 것 같으니 1시간마다 실행"

Cron은 **절대 시각을 기준**으로 동작하기 때문에 테스트가 우연히 실행 시각을 걸치면 간헐적으로 실패합니다. Scheduler 간섭을 막고 싶다면 interval을 늘리지 말고 `enabled=false`로 끕니다.

---

## 18. 테스트 데이터 Cleanup

통합 테스트가 실제 DB를 사용하면 다른 테스트와 데이터가 충돌할 수 있습니다. Cleanup 시 FK 관계를 확인하고 **자식 데이터 → 부모 데이터** 순으로 삭제합니다.

예를 들어 Event를 삭제하기 전에 Event를 참조하는 History가 존재한다면 History를 먼저 삭제해야 합니다.

단순히 마지막에 부모 Repository의 `deleteAll()`만 호출하여 **테스트가 우연히 통과하는 구조를 만들지 않습니다.**

또한 Redis와 Kafka 상태를 사용하는 통합 테스트는 DB cleanup만으로 격리됐다고 가정하지 않습니다.

---

## 19. Spring / JPA 주의사항

### 장애 복구 테스트

Redis Stream Consumer에는 Redis 연결 장애 이후 복구 로직이 존재합니다. 장애 복구 관련 기능을 변경할 때는 Mock 기반 정상 흐름만 확인하지 않고 **가능하면 실제 연결 장애를 재현**합니다.

현재 일부 테스트에서는 Testcontainers를 사용하여 Redis를 실제로 중지·재시작하는 방식으로 복구 동작을 검증합니다. Recovery 설정을 변경할 때는 다음을 함께 확인합니다.

- 최초 실패 후 retry가 시작되는가
- Retry chain이 중복 생성되지 않는가
- Backoff가 증가하는가
- 최대 delay를 넘지 않는가
- 정상 연결 복구 후 failure count가 초기화되는가
- 기존 Stream 처리로 정상 복귀하는가

### 그 밖의 주의사항

| 항목 | 주의사항 |
| --- | --- |
| `@Transactional` self-invocation | 동일 Bean 내부 호출은 Proxy를 우회. 별도 Bean 분리를 검토 |
| Bulk DML과 Persistence Context | `@Modifying` Query는 Entity를 자동 갱신하지 않음. `clear()` 또는 재조회 필요 여부 확인 |
| Bulk DML과 Auditing | Entity Callback을 거치지 않으므로 `updatedAt` 등은 직접 UPDATE 대상에 포함 |
| Path Variable Validation | `@PathVariable`, `@RequestParam` Constraint 적용에는 `@Validated` 필요 |
| Jackson 3 | Spring Boot 4.1 계열이므로 과거 Jackson 2 예제의 `com.fasterxml.jackson...` import를 그대로 복사하지 않고 현재 코드베이스의 방식을 따름 |

---

## 20. Git 컨벤션

### Branch 구조

```text
main
 └── dev
      ├── feat/{issue번호}-{기능명}
      ├── fix/{issue번호}-{기능명}
      ├── hotfix/{기능명}
      └── release/{버전}
```

| Branch | 용도 |
| --- | --- |
| `main` | 최종 배포 및 안정 버전 |
| `dev` | 개발 내용 통합 (기본 브랜치) |
| `feat/` | 새로운 기능 개발 |
| `fix/` | 버그 수정 |
| `hotfix/` | 긴급 버그 수정 |
| `release/` | 배포 준비 |

### Branch Naming

```text
feat/12-coupon-issue
feat/15-coupon-management

fix/21-duplicate-coupon

hotfix/login-error

release/v1.0.0
```

### 작업 순서

```text
Issue 생성
      ↓
Issue 번호 확인
      ↓
Feature Branch 생성
      ↓
기능 개발
      ↓
Commit
      ↓
Pull Request
      ↓
Code Review
      ↓
dev Merge
      ↓
Issue Close
```

### Commit Convention

| Prefix | 설명 |
| --- | --- |
| `feat` | 새로운 기능 추가 |
| `fix` | 버그 수정 |
| `refactor` | 코드 리팩토링 |
| `docs` | 문서 수정 |
| `style` | 코드 스타일 수정 |
| `test` | 테스트 코드 |
| `chore` | 설정 및 기타 작업 |

```text
feat: 쿠폰 발급 기능 구현
fix: 중복 발급 오류 수정
refactor: 쿠폰 검증 로직 분리
docs: API 명세 수정
test: 쿠폰 발급 테스트 추가
chore: Redis 설정 추가
```

> Commit 메시지에는 `코드 수정`, `기능 수정`처럼 포괄적인 표현을 사용하지 않고, **변경한 대상과 내용을 구체적으로** 작성합니다.

### 협업 규칙

- 작업 시작 전 GitHub Issue를 생성합니다
- 모든 기능 개발은 **Issue 기반 Feature Branch**에서 진행합니다
- `main`, `dev` 브랜치에는 **직접 Push하지 않습니다**
- 하나의 Commit에는 하나의 작업만 포함합니다
- 작업 완료 후 Pull Request를 생성합니다
- **최소 1명 이상의 코드 리뷰** 후 Merge합니다
- Merge 전 최신 `dev` 브랜치를 반영하여 충돌을 해결합니다
- Merge는 **Squash Merge** 방식을 사용합니다
- Merge 완료 후 해당 **Issue를 Close**합니다

---

## 21. Issue 작성

기능 작업은 저장소의 [Issue Template](../.github/ISSUE_TEMPLATE/feature.md) 구조를 따릅니다.

제목은 `[Feat]`, `[Fix]` 형식입니다.

```text
📌 작업 내용
📋 상세 작업
🎯 목표
📎 참고 사항
```

`상세 작업`에는 구현해야 할 작업을 Checklist로 작성합니다.

```markdown
- [ ] Repository Query 추가
- [ ] Service 구현
- [ ] Controller 추가
- [ ] 단위 테스트
- [ ] 통합 테스트
```

Issue의 `목표`는 "코드를 추가한다"보다 **해당 작업이 완료됐을 때 무엇이 보장되어야 하는지**를 적습니다.

| | 예시 |
| --- | --- |
| 나쁜 예 | Redis 로직 구현 |
| 좋은 예 | 동시에 여러 발급 요청이 들어와도 쿠폰 재고가 음수가 되지 않는다 |

---

## 22. Pull Request 작성

Pull Request는 저장소의 [PR Template](../.github/pull_request_template.md)을 사용합니다.

제목은 `[TYPE] 작업 내용 (#이슈번호)` 형식입니다.

```text
[FEAT] 쿠폰 발급 기능 구현 (#12)
[FIX] 중복 발급 오류 수정 (#21)
[REFACTOR] 쿠폰 서비스 리팩토링 (#18)
```

본문 항목은 다음과 같으며, 관련 Issue는 `Closes #123` 형태로 연결합니다.

```text
📌 작업 내용
🔗 Related Issue
✅ 변경 사항
💬 리뷰 포인트
📎 참고 사항
```

### 리뷰 포인트에 적어야 하는 내용

단순히 "리뷰 부탁드립니다"라고 작성하기보다 **이번 변경에서 실제 검토가 필요한 위험 지점**을 적습니다.

**동시성 변경이라면**

```text
- Coupon → CouponStock Lock 순서를 유지했습니다.
- 동시에 두 요청이 들어올 때 Conditional UPDATE 결과가 1건만 성공하는지 확인 부탁드립니다.
```

**Redis / DB 변경이라면**

```text
- DB commit 후 Redis 갱신 실패 시 남는 상태를 확인 부탁드립니다.
- 재호출 시 복구 가능한지 확인 부탁드립니다.
```

**Kafka 변경이라면**

```text
- 중복 메시지가 전달되어도 DB에 한 번만 반영되는지 확인 부탁드립니다.
- Retry 이후 DLQ 전환 조건을 확인 부탁드립니다.
```

**Scheduler 변경이라면**

```text
- 한 번 실패해도 다음 실행이 가능한지 확인 부탁드립니다.
- 다른 @SpringBootTest에 영향을 주지 않는지 확인 부탁드립니다.
```

---

## 23. 변경 종류별 필수 확인

코드를 변경했을 때 "어떤 테스트를 해야 하는지"를 **작업자의 기억에 맡기지 않습니다.**

### API / DTO 변경

```text
Controller Test → Validation → CustomResponse → ErrorCode → Service Test
```

외부에 공개되는 Endpoint가 추가 또는 변경되었다면 **루트 README의 주요 API 표도 확인합니다.**

### Entity / Repository 변경

```text
Entity → Constraint / Index → Repository Query → 실제 MySQL 동작 → 관련 Service Test
```

Index나 Unique Constraint가 바뀌면 현재 `ddl-auto` 의존성도 같이 확인합니다.

### ErrorCode 추가

```text
같은 Domain + HTTP Status ErrorCode 검색
→ 사용하지 않은 sequence 선택
→ GlobalExceptionHandler 경로 확인
→ Controller / Service 실패 테스트
```

### Coupon Admin 수정 로직 변경

```text
Coupon Lock → CouponStock Lock → 고정 Lock 순서 유지
→ DB 변경 → Redis 상태 동기화 → 동시 수정 테스트
```

### Redis Lua 변경

```text
Lua Script → Redis Key → Result Enum → Lua Service
→ Restore Script → Reset → Lua Integration Test → Concurrency Test
```

### Redis Stream Consumer 변경

```text
정상 Consume → 처리 실패 → Pending → Recovery
→ Max Delivery Count → Redis Stream DLQ → Redis 장애 후 재연결
```

### Kafka / Outbox 변경

```text
Outbox 저장 → Publisher → Kafka → Consumer → Persister
→ 중복 전달 → Retry → Kafka DLQ
```

Kafka 관련 timeout을 바꾼다면 **Outbox retry까지 포함한 전체 장애 시간**을 계산합니다.

### Idempotency 변경

```text
최초 요청 → 동일 요청 재전송 → 동시에 동일 Key 요청
→ 같은 Key + 다른 요청 → IN_PROGRESS → SUCCESS → FAILED
```

단위 테스트만이 아니라 **동시성 통합 테스트도** 확인합니다.

### Scheduler 변경

```text
Service Unit Test → 실제 상태 전이 → Conditional UPDATE
→ 실행 순서 → Toggle → Integration Test → 다른 테스트 Scheduler 비활성화
```

### Reconciliation Batch 변경

```text
Job 구성 → Step → Chunk / Tasklet → Report
→ Job Restart → DataSource wiring → Scheduler enable/disable
```

### 관리자 인증 변경

```text
Session 발급 → 정상 Token → Token 없음 → 잘못된 Token
→ 만료 Token → 인증 제외 Endpoint → /admin/** 보호 여부
```

### Monitoring / SSE 변경

```text
Log Event Mapping → Queue → SSE 전송 → 느린 Client
→ Heartbeat → 인증 → 연결 상한 → JSON 오류 응답
```

---

## 24. 변경 시 지켜야 하는 핵심 불변조건

다음 항목은 단순 코드 스타일이 아니라 **현재 시스템의 정합성과 장애 복구를 위해 유지해야 하는 규칙**입니다.

1. 쿠폰의 Redis 재고 차감과 중복 신청 확인을 여러 Redis 명령으로 분리하지 않습니다.
2. Kafka 메시지는 중복 전달될 수 있다고 가정합니다.
3. DB Unique Constraint를 애플리케이션 사전 조회로 대체하지 않습니다.
4. 기존 Conditional UPDATE를 단순 `find → save`로 변경하지 않습니다.
5. 관리자 쿠폰 수정의 `Coupon → CouponStock` Lock 순서를 뒤집지 않습니다.
6. DB와 Redis가 하나의 Transaction에 참여한다고 가정하지 않습니다.
7. Transaction을 보장하려고 같은 Bean 내부 호출에 `@Transactional`만 추가하지 않습니다.
8. 발급 DB 확정 Transaction에 부가적인 Notification 실패를 결합하지 않습니다.
9. Scheduler Transaction 내부에서 Exception을 무조건 삼키지 않습니다.
10. Scheduler 실행 순서를 단순 리팩터링 목적으로 변경하지 않습니다.
11. 테스트에서 Scheduler 간섭을 막기 위해 Cron 주기만 길게 설정하지 않습니다.
12. Bulk DML 이후 Persistence Context와 Auditing 상태를 확인합니다.
13. Pipeline에 처리 중인 메시지가 남은 상태에서 Internal Reset을 진행하지 않습니다.
14. Redis 발급 상태를 삭제한 후 재고 초기화 없이 발급 Pipeline을 다시 열지 않습니다.
15. ErrorCode sequence를 기존 코드 확인 없이 재사용하지 않습니다.
16. Migration 없이 `JPA_DDL_AUTO`를 `validate`로 변경하지 않습니다.

---

## 25. 개발 완료 기준

작업을 완료하기 전에 최소한 다음을 확인합니다.

```text
[ ] 관련 Issue의 요구사항을 만족한다.
[ ] 변경한 계층의 단위 테스트를 작성하거나 수정했다.
[ ] DB / Redis / Kafka 연결이 필요한 변경은 통합 테스트를 확인했다.
[ ] 동시성에 영향을 주는 변경은 동시 요청을 검증했다.
[ ] 비동기 처리는 최종 상태를 기준으로 검증했다.
[ ] 관련 없는 Scheduler가 테스트 데이터에 개입하지 않는다.
[ ] 새로운 ErrorCode가 기존 코드와 충돌하지 않는다.
[ ] Redis / DB 양쪽을 변경했다면 부분 실패 시 상태를 검토했다.
[ ] 공개 API 또는 주요 설정이 변경되었다면 README를 확인했다.
[ ] 전체 영향 범위가 넓다면 ./gradlew test를 실행했다.
[ ] PR의 리뷰 포인트에 위험한 변경 사항을 명시했다.
```

최종적으로 개발자가 확인해야 하는 흐름은 다음과 같습니다.

```text
코드 변경
   │
   ├─ API 계약 변경?
   │      └─ Controller / DTO / Validation / README 확인
   │
   ├─ DB 상태 변경?
   │      └─ Transaction / Constraint / Lock / Conditional UPDATE 확인
   │
   ├─ Redis 변경?
   │      └─ Lua / Key / 복구 / Reset / 동시성 확인
   │
   ├─ Kafka 변경?
   │      └─ Outbox / 중복 처리 / Retry / DLQ 확인
   │
   ├─ Scheduler 변경?
   │      └─ 재실행 안전성 / Toggle / 테스트 격리 확인
   │
   └─ Batch 변경?
          └─ Restart / Report / DataSource / Scheduler 확인
              │
              ↓
        관련 테스트 실행
              │
              ↓
          PR 리뷰 가능
```
