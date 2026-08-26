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

발급 API는 비동기입니다. 요청은 Redis Stream에 적재되고 당첨 여부는 Consumer가 나중에 판정하므로, **응답은 재고가 남았든 소진됐든 항상 `202 Accepted + status="WAITING"`** 입니다. `202`는 "접수했다"는 뜻이지 "발급됐다"가 아닙니다.

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

**`LIMIT`은 쏠 요청 수보다 넉넉하게** 잡습니다. 요청 하나당 회원 하나를 쓰므로 그 이상은 필요 없습니다. 아래는 본 측정(20,000건) 기준입니다.

```bash
docker exec petcoupon-mysql mysql -uroot -proot petcoupon -N -B -e "SELECT user_id FROM app_user WHERE role='ROLE_MEMBER' ORDER BY user_id LIMIT 30000" > load-test/k6/members.csv
```

PowerShell에서는 인코딩을 지정해야 합니다.

```powershell
docker exec petcoupon-mysql mysql -uroot -proot petcoupon -N -B -e "SELECT user_id FROM app_user WHERE role='ROLE_MEMBER' ORDER BY user_id LIMIT 30000" | Out-File -Encoding ascii load-test/k6/members.csv
```

30,000줄이면 약 210KB입니다. `LIMIT` 없이 100만 건을 다 뽑으면 7MB가 되고, k6가 기동할 때마다 전부 파싱합니다. `.gitignore`에 들어 있어 커밋되지 않으며, 회원을 다시 넣었다면 이 파일도 다시 만듭니다.

모자라면 `setup()`이 이렇게 알려주고 멈춥니다.

```
회원이 모자랍니다. 필요=20000 보유=10000
```

### 5. 대상 쿠폰 준비

관리자 API로 쿠폰을 하나 만들어 두고 그 ID를 `COUPON_ID`로 넘깁니다. 재고 규모는 초기화 API가 회차마다 바꿔주므로, 쿠폰을 여러 개 만들 필요는 없습니다.

**아래 실행 예시의 `COUPON_ID=1`은 자리표시자입니다.** 각자 만든 쿠폰 ID로 바꿔야 하고, 안 바꾸면 전부 404가 납니다. 만들어 둔 쿠폰이 뭔지 모르겠으면 이렇게 확인합니다.

```bash
docker exec petcoupon-mysql mysql -uroot -proot petcoupon -e "SELECT c.coupon_id, c.name, c.status, s.total_quantity FROM coupon c JOIN coupon_stock s ON s.coupon_id = c.coupon_id"
```

---

## 실행

### 스모크 (10건, 스크립트가 도는지만 확인)

```bash
k6 run -e SCENARIO=smoke -e COUPON_ID=1 -e TOTAL_QUANTITY=5 -e RUN_ID=smoke1 load-test/k6/issue-coupon.js
```

### 본 측정 (재고 10,000에 동시 사용자 20,000명)

```bash
k6 run -e SCENARIO=burst -e VUS=20000 -e ITERATIONS_PER_VU=1 -e COUPON_ID=1 -e TOTAL_QUANTITY=10000 -e RUN_ID=run1 load-test/k6/issue-coupon.js
```

`2,000 VU × 10회`는 총 20,000건을 보내지만 동시 사용자는 최대 2,000명이다. 동시 사용자 20,000명을 검증하려면 `20,000 VU × 1회`로 실행한다. 한 대의 부하 발생기가 20,000 VU를 감당하지 못하면 아래처럼 두 대로 나눈다.

### 처리량 한계 (초당 요청 수 고정)

```bash
k6 run -e SCENARIO=rate -e RATE=2000 -e DURATION=30s -e VUS=2000 -e COUPON_ID=1 load-test/k6/issue-coupon.js
```

### 환경변수

| 이름 | 기본값 | 설명 |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | 대상 서버 |
| `COUPON_ID` | `1` | 대상 쿠폰 |
| `TOTAL_QUANTITY` | `10000` | 초기화 때 되돌릴 총재고 |
| `SCENARIO` | `smoke` | `smoke` / `burst` / `rate` |
| `VUS` | `20000` | 동시 사용자 수 |
| `ITERATIONS_PER_VU` | `1` | VU당 요청 수 (총 요청 = VUS × 이 값) |
| `RATE`, `DURATION` | `1000`, `30s` | `rate` 시나리오의 목표 초당 요청 수와 지속 시간 |
| `RATE_PRE_ALLOCATED_VUS` | `RATE` 값 | `rate` 시작 시 미리 할당할 VU 수 |
| `RATE_MAX_VUS` | `max(RATE_PRE_ALLOCATED_VUS, VUS)` | 응답 지연 시 늘어날 수 있는 최대 VU 수 |
| `MEMBER_IDS_FILE` | `./members.csv` | 회원 ID 목록 |
| `RUN_ID` | `local` | 멱등키 접두사. 회차마다 바꿉니다 |
| `RESET` | `true` | `setup`에서 초기화 API 호출 여부 |
| `INSTANCE_INDEX` | `0` | k6를 여러 대로 돌릴 때 기기 번호 |
| `INSTANCE_STRIDE` | `100000` | 기기별 회원 구간 폭. **회원 목록 크기에 맞춰 줄여야 합니다** |

---

## 초기화

`setup()`이 `POST /internal/coupons/{couponId}/reset`을 호출해 되돌립니다.

| 대상 | 내용 |
| --- | --- |
| DB | 발급 · 이력 · 멱등키 · Outbox · 검증 리포트 삭제, 재고 원복 |
| Redis | 신청자 · 순번 키 삭제, 재고 키를 총재고로 재설정 |

응답의 `redisStock`은 **초기화 후 Redis에서 다시 읽은 값**입니다. `totalQuantity`와 다르면 초기화가 덜 끝난 것이고, `setup()`이 이 값을 대조해 어긋나면 즉시 실패합니다. 쓴 값을 그대로 돌려주는 게 아니라 실제 저장된 값이라 검증에 쓸 수 있습니다.

### 여러 대에서 나눠 쏠 때

**초기화를 부하와 같은 실행에 섞지 마세요.** k6에는 기기 간 동기화가 없어서, 1번 기기가 초기화를 끝내기 전에 2번 기기의 요청이 먼저 들어갈 수 있습니다. 그러면 그 요청들은 초기화에 지워지거나 이전 회차 상태에서 판정됩니다.

**초기화를 별도 단계로 떼어내고, 끝난 뒤 전 기기를 `RESET=false`로 시작합니다.**

```bash
curl -X POST localhost:8080/internal/coupons/1/reset \
  -H "Content-Type: application/json" \
  -d '{"totalQuantity": 10000}'
```

응답의 `redisStock`이 `totalQuantity`와 같은지 확인한 뒤, 각 기기에서 이렇게 띄웁니다.

```bash
# 1번 기기
k6 run -e RESET=false -e INSTANCE_INDEX=0 -e INSTANCE_STRIDE=10000 -e VUS=10000 -e ITERATIONS_PER_VU=1 ... load-test/k6/issue-coupon.js
```

```bash
# 2번 기기
k6 run -e RESET=false -e INSTANCE_INDEX=1 -e INSTANCE_STRIDE=10000 -e VUS=10000 -e ITERATIONS_PER_VU=1 ... load-test/k6/issue-coupon.js
```

> ⚠️ **`INSTANCE_STRIDE`는 회원 목록 크기에 맞춰야 합니다.** 기본값 `100000`을 그대로 쓰면서 회원을 30,000명만 뽑으면, 2번 기기가 100,000번째 회원부터 쓰려다 `setup()`에서 막힙니다.
>
> `INSTANCE_STRIDE`는 **한 기기가 사용할 회원 수(`requiredMembers`) 이상**으로 잡고, **`INSTANCE_STRIDE × 기기 수` 이상**의 회원을 뽑으면 됩니다. 이 조건을 어기면 `setup()`이 구간 중복을 방지하기 위해 실행을 중단합니다. 위 예시는 기기당 10,000명이라 `INSTANCE_STRIDE=10000`, 회원은 20,000명 이상 필요합니다.
>
> ```bash
> ... "SELECT user_id FROM app_user WHERE role='ROLE_MEMBER' ORDER BY user_id LIMIT 30000" > load-test/k6/members.csv
> ```
>
> `RUN_ID`는 전 기기가 같은 값을 써도 됩니다. 멱등키에 `INSTANCE_INDEX`가 들어가 기기끼리 겹치지 않습니다.

### 초기화가 `409`로 거절될 때 — 미처리 메시지

초기화 API는 **DB와 Redis 발급 상태까지만** 되돌립니다. **Redis Stream과 Kafka에 쌓인 메시지는 지우지 않습니다.**

그대로 지우면 지난 회차 신청이 뒤늦게 처리되며 이번 회차 재고를 깎기 때문에, **API가 먼저 검사해서 남아 있으면 거절합니다.**

```json
{ "isSuccess": false, "code": "COUPON409-8", "message": "앞 회차 메시지가 아직 처리 중이라 초기화할 수 없습니다." }
```

거절당했다면 아래를 읽고 큐를 비운 뒤 다시 부릅니다. **API가 Kafka LAG은 보지 못하므로**, 확인 명령은 거절 여부와 관계없이 한 번씩 봐두는 게 안전합니다.

앞 회차가 중간에 끊겼다면(앱 강제 종료, Consumer 다운, k6 Ctrl+C) 처리되지 않은 신청이 큐에 남습니다. 남는 방식이 **두 가지**이고 결과가 정반대이므로 나눠서 봅니다.

| | 상태 | 다음 회차에 | 결과 |
| --- | --- | --- | --- |
| **A** | 배달됐지만 ACK 안 됨 (pending) | 아무도 안 가져감 | **신청 유실** |
| **B** | 아직 처리 안 됨 (Stream 미배달 · Outbox 미발행 · Kafka 미소비) | 뒤늦게 처리됨 | **유령 발급** |

**B가 초기화를 막는 쪽입니다.** 초기화가 `coupon_issue`를 모두 지운 뒤라 `uk_issue_coupon_user`·`uk_issue_sequence`·`request_id` 유니크 제약이 아무것도 막지 못합니다. 지난 회차 신청이 이번 회차 재고를 깎으며 저장됩니다. **API가 거절하는 것도 B가 남았을 때입니다.**

**A는 반대로 영원히 처리되지 않습니다.** Consumer 이름이 기동할 때마다 바뀌어서 죽은 Consumer가 잡고 있던 pending은 아무도 회수하지 않습니다. 재처리되지 않으니 유령 발급은 안 만들고, **그래서 API도 A로는 막지 않습니다.** 다만 값이 0이 아니라는 것 자체가 "앞 회차가 깨끗하게 안 끝났다"는 신호이고 그만큼의 신청이 판정 없이 사라졌다는 뜻이라, 결과를 읽을 때 감안해야 합니다.

B가 **파이프라인 세 지점**에 나뉘어 있다는 점이 중요합니다. 한 곳만 봐서는 부족합니다.

```
Redis Stream ──▶ Lua ──▶ Outbox(DB) ──▶ Kafka ──▶ Consumer ──▶ MySQL
     ①                       ②            ③
  아직 안 읽힘          아직 발행 안 됨   나갔지만 안 먹힘
```

특히 ②는 **Redis에도 Kafka에도 안 보입니다.** `issue_message`에 `PENDING`/`FAILED`로 앉아 있다가 Outbox poller가 **1초 안에** 집어서 Kafka로 보냅니다. 그리고 `kafkaTemplate.send()`는 DB 트랜잭션 밖이라, 초기화가 그 row를 지워도 **이미 나간 Kafka 메시지는 되돌릴 수 없습니다.**

**아래 네 값이 모두 0인지 확인한 뒤에 초기화합니다.**

```bash
docker exec petcoupon-redis redis-cli XINFO GROUPS coupon:issue:stream
```

```bash
docker exec petcoupon-redis redis-cli XPENDING coupon:issue:stream coupon-issue-group
```

```bash
docker exec petcoupon-mysql mysql -uroot -proot petcoupon -N -e "SELECT COUNT(*) FROM issue_message WHERE status IN ('PENDING','FAILED')"
```

```bash
docker exec petcoupon-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group petcoupon
```

| 명령 | 볼 값 | 무엇 |
| --- | --- | --- |
| `XINFO GROUPS` | `lag` | **B ①** — Stream 미배달 건수 |
| `XPENDING` | 첫 줄 | **A** — Stream pending 건수 |
| `SELECT COUNT(*)` | 결과 | **B ②** — Outbox 미발행 건수 |
| `kafka-consumer-groups.sh` | 모든 파티션 `LAG` | **B ③** — Kafka 미소비 건수 |

#### B가 남아 있다면

**앱과 Consumer를 띄운 채로 네 값이 모두 0이 될 때까지 기다린 뒤** 초기화합니다. 정상 메시지라면 곧 처리되어 빠집니다. 기다리지 않고 초기화하면 그대로 유령 발급이 됩니다.

②는 `retry_count`가 `max-retry-count`(기본 5)에 도달하면 poller가 더 이상 집지 않아 `FAILED`로 영원히 남습니다. 이때는 기다려도 0이 되지 않으므로, Kafka가 살아 있는지 확인하고 앱을 재시작해 재발행시키거나 해당 row를 지웁니다.

#### 그래도 안 빠지면 — `force`

되찾을 수 없는 잔여물이라고 판단되면 검사를 건너뛰고 강행할 수 있습니다.

```bash
curl -X POST localhost:8080/internal/coupons/1/reset \
  -H "Content-Type: application/json" \
  -d '{"totalQuantity": 10000, "force": true}'
```

**켜고 돌린 회차의 결과는 신뢰할 수 없습니다.** 남아 있던 메시지가 뒤늦게 처리되면 발급 건수가 재고를 넘거나 순번이 어긋납니다. 측정용이 아니라 환경을 되돌리는 용도로만 씁니다. k6 스크립트에는 이 옵션이 없습니다 — 사람이 판단해야 하는 값이라 일부러 넣지 않았습니다.

#### A가 남아 있다면 (`XPENDING`)

**⚠️ 앱을 재시작해도 없어지지 않습니다.** Consumer 이름이 `${HOSTNAME}-${random.uuid}`라 기동할 때마다 새 이름이 붙습니다. 새 Consumer는 `ReadOffset.lastConsumed()`로 **"그룹에 배달된 적 없는 메시지"만** 읽어서, 이전 이름으로 잡혀 있던 pending은 쳐다보지 않습니다. `XCLAIM`으로 소유권을 가져오는 코드도 없어 계속 쌓이기만 합니다.

남은 ID를 확인해서 직접 ACK합니다.

```bash
docker exec petcoupon-redis redis-cli XPENDING coupon:issue:stream coupon-issue-group - + 100
```

```bash
docker exec petcoupon-redis redis-cli XACK coupon:issue:stream coupon-issue-group 1787674082855-0
```

건수가 많으면 스트림을 통째로 비우고 그룹을 다시 만듭니다. A와 B가 한 번에 정리됩니다. **다른 사람의 테스트 기록까지 지워지므로 공용 환경에서는 먼저 물어보고 실행합니다.**

```bash
docker exec petcoupon-redis redis-cli DEL coupon:issue:stream
```

```bash
docker exec petcoupon-redis redis-cli XGROUP CREATE coupon:issue:stream coupon-issue-group 0 MKSTREAM
```

`MKSTREAM` 없이 실행하면 키가 없다며 실패합니다. 그룹을 다시 만들지 않으면 다음 신청부터 Consumer가 `NOGROUP`으로 멈춥니다.

> `issue_message` 테이블로는 이 확인을 대신할 수 없습니다. 상태가 `SENT`에서 멈추고 `CONSUMED`로 바꾸는 코드가 없어서, "Kafka에 넣었다"까지만 알 수 있고 처리 여부는 알 수 없습니다.

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
| `issue_bad_request` | 400. 요청이 잘못된 것 — `Idempotency-Key` 누락·64자 초과 등. 서버 문제가 아닙니다 |
| `issue_server_error` | 5xx. 서버가 응답은 했지만 처리에 실패한 것 |
| `issue_request_error` | 응답을 아예 못 받음(연결 거절 · 리셋 · 타임아웃). 서버 로그에 아무것도 안 남으면 부하 생성기 쪽 한계입니다 |

---

## 측정 전 확인

### 앱을 부하 테스트 설정으로 띄웁니다

애플리케이션 기본값은 **평상시(로컬 · 통합 테스트) 기준**이라 그대로 두면 측정이 되지 않습니다. 부하 테스트용 값은 환경변수로만 넘깁니다.

```bash
ISSUE_LOG_LEVEL=WARN TOMCAT_MAX_THREADS=2000 TOMCAT_MAX_CONNECTIONS=25000 TOMCAT_ACCEPT_COUNT=5000 DB_POOL_SIZE=100 ./gradlew bootRun
```

| 환경변수 | 기본값 | 부하 테스트 | 왜 |
| --- | --- | --- | --- |
| `ISSUE_LOG_LEVEL` | `INFO` | `WARN` | 요청마다 남는 추적 로그가 응답 시간에 섞입니다 |
| `TOMCAT_MAX_THREADS` | `200` | `2000` | 동시 요청을 받아낼 워커 스레드 |
| `TOMCAT_MAX_CONNECTIONS` | `8192` | `25000` | 동시에 열어둘 커넥션 |
| `TOMCAT_ACCEPT_COUNT` | `100` | `5000` | 대기 큐. 차면 연결이 거절됩니다 |
| `DB_POOL_SIZE` | `10` | `100` | **스레드만 올리면 안 됩니다** (아래) |

> ⚠️ **`DB_POOL_SIZE`를 빼먹으면 측정이 통째로 무너집니다.** 스레드를 2,000으로 열어도 커넥션이 10개면 1,990개가 풀 앞에 줄만 섭니다. 실제로 200 VU에서 400건 전부 30초 타임아웃 후 500이 났습니다. (아래 "확인된 것" 참고)

**적정 풀 크기는 재서 정합니다.** 1단계(재고 1,000 / 요청 2,000)를 돌리면서 아래 값이 `0`을 유지하는 선까지 올립니다.

```bash
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.pending
```

`pending`이 0보다 크면 스레드가 커넥션을 기다리고 있다는 뜻이고, 그 상태의 p95는 앱 성능이 아니라 풀 크기를 재고 있는 값입니다. 상한은 MySQL `max_connections`(부하 테스트 환경 500)이며, 앱 외에 Outbox 발행기와 스케줄러도 커넥션을 씁니다.

### 그 밖에

- **k6와 앱은 다른 인스턴스에서 돌립니다.** 같은 기기면 k6가 쓰는 CPU가 앱 응답 시간에 섞입니다.
- **발급 이력 더미데이터는 필요할 때만 적재합니다.** 수백만 건을 넣어두면 팀 전체의 테스트 실행이 느려집니다.

---

## 확인된 것 (로컬, 2026-08-26)

| 회차 | 요청 | 재고 | 접수 | 확정 | 검증 |
|---|---|---|---|---|---|
| 스모크 | 10 | 5 | 10 / 10 | 5 | 13개 항목 PASS |
| 버스트 | 400 (200 VU × 2) | 100 | 400 / 400 | 100 | 13개 항목 PASS |

부수적으로 확인된 것:

- **Tomcat 스레드와 DB 커넥션 풀은 같이 올려야 합니다.** 200 VU로 쏘면 400건 전부 `Connection is not available` → 500이 났고(풀 10), 풀만 100으로 올리자 500이 0건이 됐습니다. 스레드를 2,000으로 올려도 풀이 10이면 스레드가 풀 앞에 줄만 섭니다. 이 실측 때문에 두 값을 모두 환경변수로 빼고 기본값은 평상시 기준으로 되돌렸습니다(#86).
- 접수 응답 시간(로컬, 200 VU 기준 p95 약 3초)은 앱과 k6가 같은 PC에서 도는 값이라 그대로 쓰지 않습니다. AWS에서 인스턴스를 나눠 다시 잽니다.
