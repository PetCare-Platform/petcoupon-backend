# 부하 테스트

선착순 쿠폰 발급(`POST /coupons/{couponId}/issues`)에 부하를 주고, 그 결과가 정합한지 확인하기 위한 스크립트 모음입니다.

```
load-test/
├── k6/
│   ├── config.js          공통 설정 (환경변수로 대상 · 규모를 받음)
│   ├── issue-coupon.js    발급 API 부하 스크립트
│   └── members.csv        회원 ID 목록 (DB 에서 만들어 씀, 커밋하지 않음)
└── sql/
    ├── seed_users.sql            더미 회원 100만 명 생성
    └── verify_issue_result.sql   부하 종료 후 정합성 검증
```

---

## 이 테스트가 재는 것

발급 API는 비동기입니다. 요청은 Redis Stream에 적재되고 당첨 여부는 Consumer가 나중에 판정하므로, **응답은 재고가 남았든 소진됐든 항상 `200 + status="WAITING"`** 입니다.

| | 무엇을 보나 | 어디서 보나 |
|---|---|---|
| 접수 | 성공률, 응답 시간, 타임아웃 · 5xx | k6 요약 |
| 확정 | 발급 건수, 1인 1매, 순번, 이력 | `verify_issue_result.sql` |

**`k6 성공 응답 수 = DB 발급 건수`는 성립하지 않습니다.** 400건을 쏘면 k6는 400건 성공, DB에는 재고(100)만큼만 남는 것이 정상입니다.

---

## 준비

### 1. k6 설치

```bash
winget install k6 --source winget
```

### 2. 인프라 기동

```bash
docker compose up -d
```

로컬에 MySQL이 따로 설치돼 있으면 3306 포트가 겹쳐 컨테이너가 뜨지 않습니다. 관리자 PowerShell에서 `net stop MySQL80`으로 멈춘 뒤 실행합니다.

### 3. 더미 회원 적재 (최초 1회)

```bash
docker cp load-test/sql/seed_users.sql petcoupon-mysql:/tmp/
docker exec petcoupon-mysql mysql -uroot -proot petcoupon -e "source /tmp/seed_users.sql"
```

### 4. 회원 ID 목록 만들기

**회원 ID는 연속이 아닙니다.** 100만 건을 한 번에 넣으면 `auto_increment`가 띄엄띄엄 올라가고 관리자 계정도 중간에 끼어 있어서, 시작값에 1씩 더해 쓰면 없는 회원으로 404가 납니다. 실제 ID를 파일로 뽑아 씁니다.

```bash
docker exec petcoupon-mysql mysql -uroot -proot petcoupon -N -B -e "SELECT user_id FROM app_user WHERE role='ROLE_MEMBER' ORDER BY user_id" > load-test/k6/members.csv
```

PowerShell에서는 인코딩을 지정해야 합니다.

```powershell
docker exec petcoupon-mysql mysql -uroot -proot petcoupon -N -B -e "SELECT user_id FROM app_user WHERE role='ROLE_MEMBER' ORDER BY user_id" | Out-File -Encoding ascii load-test/k6/members.csv
```

약 7MB이고 `.gitignore`에 들어 있습니다. 회원을 다시 넣었다면 이 파일도 다시 만듭니다.

### 5. 대상 쿠폰 준비

관리자 API로 쿠폰을 하나 만들어 두고 그 ID를 `COUPON_ID`로 넘깁니다. 재고 규모는 초기화 API가 회차마다 바꿔주므로, 쿠폰을 여러 개 만들 필요는 없습니다.

---

## 실행

### 스모크 (10건, 스크립트가 도는지만 확인)

```bash
k6 run -e SCENARIO=smoke -e COUPON_ID=612 -e TOTAL_QUANTITY=5 -e RUN_ID=smoke1 load-test/k6/issue-coupon.js
```

### 본 측정 (재고 10,000에 요청 20,000)

```bash
k6 run -e SCENARIO=burst -e VUS=2000 -e ITERATIONS_PER_VU=10 -e COUPON_ID=612 -e TOTAL_QUANTITY=10000 -e RUN_ID=run1 load-test/k6/issue-coupon.js
```

### 처리량 한계 (초당 요청 수 고정)

```bash
k6 run -e SCENARIO=rate -e RATE=2000 -e DURATION=30s -e VUS=2000 -e COUPON_ID=612 load-test/k6/issue-coupon.js
```

### 환경변수

| 이름 | 기본값 | 설명 |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | 대상 서버 |
| `COUPON_ID` | `1` | 대상 쿠폰 |
| `TOTAL_QUANTITY` | `10000` | 초기화 때 되돌릴 총재고 |
| `SCENARIO` | `smoke` | `smoke` / `burst` / `rate` |
| `VUS` | `2000` | 동시 사용자 수 |
| `ITERATIONS_PER_VU` | `10` | VU당 요청 수 (총 요청 = VUS × 이 값) |
| `RATE`, `DURATION` | `1000`, `30s` | `rate` 시나리오 전용 |
| `MEMBER_IDS_FILE` | `./members.csv` | 회원 ID 목록 |
| `RUN_ID` | `local` | 멱등키 접두사. 회차마다 바꿉니다 |
| `RESET` | `true` | `setup`에서 초기화 API 호출 여부 |
| `INSTANCE_INDEX` | `0` | k6를 여러 대로 돌릴 때 기기 번호 |

---

## 초기화

`setup()`이 `POST /internal/coupons/{couponId}/reset`을 호출해 **DB**(발급 · 이력 · 멱등키 · Outbox · 검증 리포트)와 재고를 되돌립니다.

> ⚠️ **Redis는 아직 이 API가 건드리지 않습니다.** 이전 회차의 신청자 기록이 남아 있으면 다음 회차 요청이 전부 중복으로 판정돼 발급이 0건이 됩니다. 초기화 API에 Redis 정리를 붙이는 작업(#88)이 머지되기 전까지는 회차 사이에 직접 되돌립니다.
>
> ```bash
> docker exec petcoupon-redis redis-cli DEL "coupon:issue:applicants:{612}" "coupon:issue:sequence:{612}" "coupon:issue:request-sequence:{612}"
> docker exec petcoupon-redis redis-cli SET "coupon:issue:stock:{612}" 10000
> ```
>
> 재고 키는 **지우면 안 되고 값을 다시 넣어야** 합니다. 없으면 Lua가 `STOCK_NOT_INITIALIZED`로 전부 거절합니다. `{612}`는 쿠폰 ID이고, 중괄호는 Redis Cluster 해시 태그라 그대로 씁니다.

여러 대에서 나눠 쏠 때는 **1번 기기만** `RESET=true`로 두고, 나머지는 `RESET=false`에 `INSTANCE_INDEX`를 다르게 줍니다. 회원 구간과 멱등키가 겹치지 않습니다.

---

## 결과 확인

부하가 끝나도 **확정은 아직 밀려 있을 수 있습니다.** 검증 SQL의 0번 블록(미처리 Outbox)이 0이 될 때까지 기다린 뒤 본 검증을 읽습니다.

```bash
docker cp load-test/sql/verify_issue_result.sql petcoupon-mysql:/tmp/
docker exec petcoupon-mysql mysql -uroot -proot petcoupon --default-character-set=utf8mb4 -e "source /tmp/verify_issue_result.sql"
```

대상 쿠폰이 1번이 아니면 파일 맨 위의 `SET @coupon_id = 1;`을 바꿉니다. (`source`는 클라이언트 명령이라 `-e`로 `SET`을 앞에 붙일 수 없습니다.)

13개 항목이 모두 `PASS`여야 합니다.

| # | 항목 | 깨졌다면 |
|---|---|---|
| 1 | 발급 건수 = 총재고 | 재고 판정이 새거나 확정이 유실됨 |
| 2 | 1인 2매 이상 발급 | 중복 판정 실패 |
| 3~4 | 순번 중복 · 연속 | 판정은 났는데 DB 확정이 빠짐 |
| 5~6 | 쿠폰코드 · request_id 중복 | 같은 요청이 두 번 확정됨 |
| 7~8 | 이력 누락 · 상태 불일치 | 상태만 바뀌고 이력이 안 쌓임 |
| 9~10 | Outbox 건수 · 미처리 | 발행 유실 또는 중복 |
| 11~12 | 재고 카운터 | 발급과 재고가 따로 놂 |
| 13 | IN_PROGRESS 멱등키 | 응답 못 받고 끊긴 요청이 있음 |

---

## k6 지표 읽는 법

| 지표 | 의미 |
|---|---|
| `issue_accept_rate` | 접수 성공률. 0.99 미만이면 서버가 요청을 받아내지 못한 것 |
| `issue_accepted_duration` | 접수 응답 시간. 당첨 여부와 무관한 값 |
| `issue_conflict` | 409. 정상 부하에서는 0이어야 하고, 크면 멱등키 규칙을 의심 |
| `issue_not_found` | 404. 쿠폰 ID나 회원 목록이 잘못된 것 (측정 문제가 아니라 설정 문제) |
| `issue_server_error` | 5xx. 서버가 응답은 했지만 처리에 실패한 것 |
| `issue_request_error` | 응답을 아예 못 받음(연결 거절 · 리셋 · 타임아웃). 서버 로그에 아무것도 안 남으면 부하 생성기 쪽 한계입니다 |

---

## 측정 전 확인

- **로그 레벨을 낮춥니다.** 발급 경로에 요청마다 남는 추적 로그가 있어 측정에 영향을 줍니다.
  ```bash
  ISSUE_LOG_LEVEL=WARN ./gradlew bootRun
  ```
- **DB 커넥션 풀을 스레드 수에 맞춰 늘립니다.** 기본값이 10이라, 동시 요청 200건만 들어와도 전부 커넥션을 기다리다 30초 타임아웃으로 500이 납니다. (아래 "확인된 것" 참고)
  ```bash
  SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=100 ./gradlew bootRun
  ```
- **k6와 앱은 다른 인스턴스에서 돌립니다.** 같은 기기면 k6가 쓰는 CPU가 앱 응답 시간에 섞입니다.
- **발급 이력 더미데이터는 필요할 때만 적재합니다.** 수백만 건을 넣어두면 팀 전체의 테스트 실행이 느려집니다.

---

## 확인된 것 (로컬, 2026-08-26)

| 회차 | 요청 | 재고 | 접수 | 확정 | 검증 |
|---|---|---|---|---|---|
| 스모크 | 10 | 5 | 10 / 10 | 5 | 13개 항목 PASS |
| 버스트 | 400 (200 VU × 2) | 100 | 400 / 400 | 100 | 13개 항목 PASS |

부수적으로 확인된 것:

- **Hikari 커넥션 풀 기본값(10)이 병목입니다.** 200 VU로 쏘면 400건 전부 `Connection is not available` → 500이 났고, 풀을 100으로 올리자 500이 0건이 됐습니다. Tomcat 스레드를 2,000으로 올려도(#86) 커넥션 풀이 10이면 스레드가 풀 앞에 줄만 섭니다. 별도 이슈로 다룰 값입니다.
- 접수 응답 시간(로컬, 200 VU 기준 p95 약 3초)은 앱과 k6가 같은 PC에서 도는 값이라 그대로 쓰지 않습니다. AWS에서 인스턴스를 나눠 다시 잽니다.
