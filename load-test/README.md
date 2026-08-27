# 부하 테스트

선착순 쿠폰 발급(`POST /coupons/{couponId}/issues`)에 부하를 주고, 그 결과가 정합한지 확인하기 위한 스크립트 모음입니다.

```
load-test/
├── k6/
│   ├── config.js          공통 설정 (환경변수로 대상 · 규모를 받음)
│   ├── issue-coupon.js    발급 API 부하 스크립트
│   └── members.csv        회원 ID 목록 (DB 에서 만들어 씀, 커밋하지 않음)
└── sql/
    ├── seed_users.sql              더미 회원 100만 명 생성
    ├── verify_issue_result.sql     부하 종료 후 정합성 검증
    └── verify_order_inversion.sql  선착순 순서 역전율 (TC-91 · TC-93)
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
| `FIXED_USER_ID` | 없음 | **통합 테스트 전용.** 모든 요청을 이 회원으로 보냅니다 (TC-42) |
| `FIXED_IDEMPOTENCY_KEY` | 없음 | **통합 테스트 전용.** 모든 요청이 이 멱등키를 씁니다 (TC-43) |

> 마지막 두 개는 **부하 측정에서는 절대 주지 마세요.** 부하 측정은 "요청마다 다른 회원·다른 멱등키"가 전제인데, 이 값들은 그 전제를 일부러 깨서 중복 요청 처리를 검증하는 용도입니다.

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

> `issue_message` 테이블로는 이 확인(Redis Stream에 남은 미배달분)을 대신할 수 없습니다. Outbox에 행이 생기는 건 Stream Consumer가 판정을 끝낸 뒤라, 아직 Stream에 남아 있는 요청은 이 테이블에 아예 나타나지 않습니다.

---

## 통합 테스트 C·G 구간 실행

`load-test/docs/integration-test-scenario.md`의 C 구간(경계·동시성)과 G 구간(선착순 순서 보장)을 이 스크립트로 실행합니다. 부하 측정과 달리 **규모가 작고 재고를 일부러 부족하게 잡습니다.**

### 공통 준비

> ⚠️ **TC-41·TC-91·TC-94는 앱을 부하 테스트 설정으로 띄워야 합니다.** 동시 150~200건이라 기본 커넥션 풀(10)로는 감당이 안 됩니다. 실측에서 기본 설정으로 TC-41을 돌렸더니 **200건 중 191건이 500**이었고 응답이 평균 30초였습니다. 커넥션 대기 타임아웃입니다.
>
> ```bash
> DB_POOL_SIZE=100 TOMCAT_MAX_THREADS=400 ./gradlew bootRun
> ```
>
> TC-40·42·43·90은 동시 5건 이하라 기본 설정으로 됩니다.

대상 쿠폰에 **`coupon_stock` 행이 있어야 합니다.** 없으면 초기화 API가 `COUPON404-0`으로 거절합니다(쿠폰 행만 있는 것으로는 부족합니다). 준비 방법은 위 "5. 대상 쿠폰 준비" 참고.

회원 목록이 필요합니다. 요청 수보다 많으면 되므로 200명이면 충분합니다.

```bash
docker exec petcoupon-mysql mysql -uroot -proot petcoupon --batch --skip-column-names -e "SELECT user_id FROM app_user WHERE role='ROLE_MEMBER' ORDER BY user_id LIMIT 200" > load-test/k6/members.csv
```

각 TC는 **직전 TC의 데이터가 남아 있으면 안 됩니다.** 아래 명령은 모두 `RESET=true`(기본값)라 실행할 때마다 초기화됩니다. `RUN_ID`도 TC마다 다르게 주세요.

### TC별 실행 명령

모두 `load-test/k6` 디렉터리에서 실행합니다.

| TC | 시나리오 | 명령 | 확인 |
|---|---|---|---|
| TC-40 | 재고 1, 동시 2명 | `k6 run issue-coupon.js -e SCENARIO=burst -e VUS=2 -e TOTAL_QUANTITY=1 -e RUN_ID=tc40` | SQL 1번 = 1건 |
| TC-41 | 재고 100, 동시 200명 | `k6 run issue-coupon.js -e SCENARIO=burst -e VUS=200 -e TOTAL_QUANTITY=100 -e RUN_ID=tc41` | SQL 1번 = **정확히 100건**, 12번 잔여 0 |
| TC-42 | 같은 회원 동시 5회 | `k6 run issue-coupon.js -e SCENARIO=burst -e VUS=5 -e TOTAL_QUANTITY=100 -e FIXED_USER_ID=<회원ID> -e RUN_ID=tc42` | SQL 1번 = 1건, 2번 PASS |
| TC-43 | 동일 멱등키 재전송 | `k6 run issue-coupon.js -e SCENARIO=burst -e VUS=3 -e TOTAL_QUANTITY=100 -e FIXED_USER_ID=<회원ID> -e FIXED_IDEMPOTENCY_KEY=tc43-key -e RUN_ID=tc43` **+ 아래 재현 확인** | 발급 1건, 재고 1 차감, 재현 응답이 최초와 동일 |
| TC-44 | 재고 0, 동시 50명 | `k6 run issue-coupon.js -e SCENARIO=burst -e VUS=50 -e TOTAL_QUANTITY=1 -e RUN_ID=tc44a` 로 소진시킨 뒤, **2회차는 겹치지 않는 회원으로** `-e RESET=false -e INSTANCE_INDEX=1 -e INSTANCE_STRIDE=50 -e RUN_ID=tc44b` | 2회차 **전건 `COUPON409-0`**, 발급 증가 없음, 재고 음수 아님 |
| TC-90 | 순차 100건 | `k6 run issue-coupon.js -e SCENARIO=burst -e VUS=1 -e ITERATIONS_PER_VU=100 -e TOTAL_QUANTITY=100 -e RUN_ID=tc90` | 도착 순서와 `sequence_no` 완전 일치 |
| TC-91 | 동시 200건 순서 역전율 | `k6 run issue-coupon.js -e SCENARIO=burst -e VUS=200 -e TOTAL_QUANTITY=100 -e RUN_ID=tc91` | 로그 도착 시각 ↔ `sequence_no` 대조 |
| TC-93 | 저장 순서 역전 확인 | TC-91 직후 SQL 조회만 | `created_at` 순서 ≠ `sequence_no` 순서여도 정상. SQL 3·4번은 PASS |
| TC-94 | 재고 100, 요청 150 | `k6 run issue-coupon.js -e SCENARIO=burst -e VUS=150 -e TOTAL_QUANTITY=100 -e RUN_ID=tc94` | SQL 1번 = 100건, 4번 순번 1..100 |

> **TC-45·TC-46**은 `CouponIssueConcurrencyIntegrationTest`(#59)에서 이미 검증돼 k6 대상이 아닙니다.
> **TC-92**는 부하가 아니라 로그 조회라 k6를 쓰지 않습니다.

### TC-42·TC-43은 k6 요약만으로 판정하지 마세요

발급 API는 비동기라 **중복 신청도 일단 202로 접수됩니다.** 탈락 판정은 그 뒤 Lua가 합니다.

- **TC-42**: k6에는 5건 모두 202로 찍힙니다. 실제로 1건만 발급됐는지는 SQL 1번으로 봅니다.
- **TC-43**: 서버가 최초 응답을 그대로 재현하면 202, 아직 처리 중이면 409(`COUPON409-5`)입니다. **둘 중 뭐가 나올지는 타이밍에 달렸습니다.** 그래서 이 모드에서는 `issue_accept_rate`와 `issue_conflict` 임계값이 자동으로 해제됩니다. k6 회차만으로는 발급 건수까지밖에 못 봅니다.

> ⚠️ **`FIXED_IDEMPOTENCY_KEY`는 `FIXED_USER_ID`와 반드시 같이 주세요.** 멱등키 유니크 제약이 `(user_id, idempotency_key)`라서, 키만 고정하고 회원이 다르면 서버가 서로 다른 요청으로 보고 **전건을 발급합니다.** 실제로 회원 없이 3건을 쐈더니 발급이 3건 나왔습니다. 지금은 둘 중 하나만 주면 `setup()`에서 중단됩니다.

#### TC-43 재현 응답 확인 (k6 회차 뒤에 반드시 같이)

TC-43의 기대 결과에는 **"최초 순번 반환"**이 들어 있습니다. 위 k6 회차는 동시 요청만 쏘고 끝나서 *발급이 1건인지*까지만 봅니다. **확정된 뒤 같은 키로 다시 불렀을 때 최초 응답이 그대로 재현되는지는 별도로 확인해야 합니다.**

k6로 하면 안 됩니다. 재현 응답은 **200**인데 스크립트의 `issue_contract_ok` 임계값이 `202 + status=WAITING`을 요구해서 전건 실패로 찍힙니다.

검증 SQL 0번 블록의 `대기`·`재시도대기`·`발행중`이 **모두 0**이 된 걸 확인한 뒤, k6와 **똑같은 회원·똑같은 키**로 한 번 더 호출합니다.

```bash
curl -s -X POST "localhost:8080/coupons/1/issues" \
  -H "Idempotency-Key: tc43-key" -H "X-USER-ID: <k6에 준 회원ID>"
```

| 확인 | 기대 |
|---|---|
| HTTP 상태 | `200` (`202`가 아닙니다 — 새 접수가 아니라 저장된 응답의 재현입니다) |
| `couponIssueId`·`sequenceNo` | 최초 발급 건과 **동일**. `SELECT coupon_issue_id, sequence_no FROM coupon_issue WHERE coupon_id = 1`과 대조 |
| Redis 재고 | **추가 차감 없음.** `docker exec petcoupon-redis redis-cli GET "coupon:issue:stock:{1}"`이 재현 호출 전후로 같아야 합니다 |
| `coupon_issue` 행 수 | 그대로 1건 |

`status`가 `WAITING`으로 돌아오면 **파이프라인이 아직 안 끝난 겁니다.** 그 상태의 응답은 순번이 비어 있으니 0번 블록이 0이 될 때까지 기다렸다 다시 부르세요.

### 확인된 것 (로컬, 2026-08-27)

아래 명령을 실제로 돌려 나온 결과입니다. 앱은 `DB_POOL_SIZE=100 TOMCAT_MAX_THREADS=400`으로 띄웠습니다.

| TC | 조건 | 발급 | 순번 | 회원 | 잔여 |
|---|---|---|---|---|---|
| TC-40 | 재고 1, 동시 2 | 1 | 1..1 | 1 | 0 |
| TC-41 | 재고 100, 동시 200 | **100** | 1..100 | 100 | 0 |
| TC-42 | 같은 회원 동시 5 | **1** | — | 1 | — |
| TC-43 | 같은 회원+같은 키 3 | **1** | — | 1 | 99 |
| TC-90 | 재고 100, 순차 100 | 100 | 1..100 | 100 | 0 |
| TC-94 | 재고 100, 요청 150 | **100** | 1..100 | 100 | 0 |

**초과 발급 0건, 순번 빠짐·중복 0건.** TC-42는 멱등키가 `SUCCEEDED 1 / FAILED 4`로 갈렸습니다.

TC-41 기준 접수 처리량은 초당 약 67건이었고 500은 0건이었습니다.

### TC-91 · TC-93 순서 역전율 측정

부하를 쏜 뒤 아래 SQL을 돌리면 역전 건수와 비율이 나옵니다.

```bash
docker cp load-test/sql/verify_order_inversion.sql petcoupon-mysql:/tmp/
docker exec petcoupon-mysql mysql -uroot -proot --default-character-set=utf8mb4 petcoupon -e "source /tmp/verify_order_inversion.sql"
```

**로그를 볼 필요가 없습니다.** 시나리오 문서는 "서버 도착 로그"라고 적고 있지만 컨트롤러에는 진입 로그가 없습니다. 대신 `request_id`(`issue:{idempotency_id}`)의 번호를 씁니다.

> ⚠️ **이 번호는 HTTP 도착 순서가 아닙니다.** `begin()` 처리 중 `idempotency_key` INSERT가 실행되어 `AUTO_INCREMENT`를 할당받은 순서입니다. HTTP가 먼저 도착한 요청이라도 그 사이에 톰캣 스레드 배정과 쿠폰·회원 존재 확인(MySQL 왕복 2회)을 거치면서 순서가 뒤집힐 수 있습니다.
>
> 따라서 역전율은 **요청 도착 순서에 대한 공정성을 증명하지 않습니다.** 멱등키 등록 순서와 Lua `sequence_no` 사이의 역전 정도를 재는 참고 지표로만 씁니다.

실측 예시 (재고 100, 동시 200건):

```
발급 건수          100
비교 쌍            4950
역전 쌍            1023
역전율(%)          20.67
순번 1..N 무결성   PASS
  └ 검사 범위      DB 내부만(꼬리 유실 미검출) — verify_issue_result.sql 1번을 함께 볼 것
```

> **역전율은 판정 대상이 아닙니다.** 위에 적은 이유로 앞의 순서 자체가 확정적이지 않아 기준값이 없습니다(시나리오 문서 G 구간). 기록만 하면 됩니다.
>
> **반드시 지켜져야 하는 건 `순번 1..N 무결성` 하나입니다.**

### 꼬리 유실까지 잡으려면

기본 상태(`@expected_issued_count = NULL`)에서는 **DB에 있는 것끼리만** 봅니다. 그래서 중간이 빈 것은 잡지만 **마지막 번호가 통째로 빠진 것은 못 잡습니다** — `1..100` 중 100번만 유실되면 남은 `1..99`가 그 자체로 온전해 보이기 때문입니다.

Lua가 몇 번까지 내줬는지는 Redis에만 있어서 SQL이 스스로 알 수 없습니다. 엄밀히 보려면 그 값을 읽어 SQL 상단에 넣습니다.

```bash
docker exec petcoupon-redis redis-cli GET "coupon:issue:sequence:{1}"
```

```sql
SET @expected_issued_count = 100;
```

값을 넣지 않아도 **`verify_issue_result.sql` 1번 항목**이 `발급 건수 = MIN(접수 요청, 총재고)`로 같은 문제를 잡습니다. 그래서 이 값은 선택입니다.

TC-93(저장 순서 역전)은 같은 데이터로 확인합니다. `created_at` 순서가 `sequence_no`와 달라도 정상이며, 비동기 구조에서 당연한 결과입니다.

---

## 결과 확인

부하가 끝나도 **확정은 아직 밀려 있을 수 있습니다.** 검증 SQL의 0번 블록에서 `대기`·`재시도대기`·`발행중`이 **모두 0**이 될 때까지 기다린 뒤 본 검증을 읽습니다.

`발행중`은 Kafka에 넣기만 하고 아직 DB 저장이 안 끝난 건입니다. 이게 남아 있는데 본 검증을 읽으면 발급 건수와 순번이 실제보다 적게 나와 없는 문제를 만들어냅니다.

```bash
docker cp load-test/sql/verify_issue_result.sql petcoupon-mysql:/tmp/
docker exec petcoupon-mysql mysql -uroot -proot petcoupon --default-character-set=utf8mb4 -e "source /tmp/verify_issue_result.sql"
```

대상 쿠폰이 1번이 아니면 파일 맨 위의 `SET @coupon_id = 1;`을 바꿉니다. (`source`는 클라이언트 명령이라 `-e`로 `SET`을 앞에 붙일 수 없습니다.)

13개 항목이 모두 `PASS`여야 합니다.

| # | 항목 | 깨졌다면 |
|---|---|---|
| 1 | 발급 건수 = MIN(접수 요청, 총재고) | 재고 판정이 새거나 확정이 유실됨 |
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
