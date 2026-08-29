# 부하 테스트 시나리오

## 1. 목적

**구현한 시스템이 대규모 동시 요청에서 정합성을 유지하는지 증명한다.**

이전 실험이 "어떤 전략이 나은가"를 고르는 것이었다면, 이번 테스트는 "우리가 만든 것이 실제로 버티는가"를 확인한다.

| 우선순위 | 확인 항목 |
| --- | --- |
| 1 | **정합성** — 초과 발급·중복 발급·순번 충돌이 단 1건도 없는가 |
| 2 | **멱등성** — 같은 요청이 재전송돼도 발급이 1건인가 |
| 3 | 성능 — 접수 응답이 충분히 빠른가, 확정이 제때 끝나는가 |

정합성이 깨지면 성능 수치가 아무리 좋아도 **실패**로 판정한다.

## 2. 실행 환경

### 구성

```
[EC2 C] k6 부하 발생기
    │
    ▼
[EC2 A] Spring Boot 애플리케이션
    │
    ▼
[EC2 B] MySQL 8.0 + Redis 7.2 + Kafka 3.7 (Docker)
```

| 항목 | 이유 |
| --- | --- |
| k6를 별도 인스턴스로 분리 | 앱 서버에서 돌리면 부하 발생기가 CPU를 점유해 측정이 왜곡된다 |
| 앱과 DB·Redis·Kafka를 분리 | 한 대에 올리면 병목이 앱인지 인프라인지 구분되지 않는다 |
| 같은 VPC · 같은 AZ | 인터넷 왕복 지연이 응답 시간에 섞이지 않는다. AZ 가 다르면 그만큼 지연이 붙는다 |
| RDS·ElastiCache·MSK 대신 EC2 + Docker | 로컬과 동일한 구성을 그대로 써서 재현성을 확보한다 |

> ⚠️ **Kafka 는 `docker compose up -d` 로 안 뜬다.** `docker-compose.yml` 에 정의는 있지만 `profiles: [kafka]` 로 묶여 있어서, 프로파일을 함께 줘야 기동한다.
>
> ```bash
> docker compose --profile kafka up -d
> ```
>
> 파이프라인의 `Outbox → Kafka → Consumer → MySQL` 구간이 전부 여기 걸려 있다. **빼먹으면 접수는 202 로 정상 응답하는데 발급이 한 건도 확정되지 않는다** — 앱도 API 도 멀쩡해 보여서 원인을 찾는 데 시간이 걸린다(통합 테스트 TC-74 참고).
>
> compose 주석은 "애플리케이션에 Kafka 코드가 들어오면 profiles 항목을 제거한다"고 되어 있는데 그 코드는 이미 들어왔다. `profiles` 를 빼서 기본 기동에 포함시키는 게 맞다 — 별도 이슈 대상이다.

### 로컬 PC에서 부하를 발생시키지 않는 이유

| 제약 | 내용 |
| --- | --- |
| TCP 포트 | Windows 기본 동적 포트가 16,384개(49152~65535). 연결 하나당 포트 하나를 쓰므로 동시 50,000 연결이 불가능하다. 닫힌 연결도 `TIME_WAIT`으로 약 2분간 포트를 점유한다 |
| 네트워크 지연 | 집 → AWS 왕복이 10~30ms. 같은 VPC 내부는 1ms 미만. 응답 시간에 인터넷 변수가 섞여 재현이 되지 않는다 |

소규모 통합 테스트(200 요청 수준)는 로컬에서 실행해도 무방하다.

### 사전 설정

앱 기본값은 **평상시(로컬·통합 테스트) 기준**이라 그대로 두면 측정이 되지 않는다. 부하 테스트 값은 전부 환경변수로만 넘긴다.

```bash
ISSUE_LOG_LEVEL=WARN \
TOMCAT_MAX_THREADS=2000 TOMCAT_MAX_CONNECTIONS=25000 TOMCAT_ACCEPT_COUNT=5000 \
DB_POOL_SIZE=100 \
COUPON_RECONCILIATION_SCHEDULER_ENABLED=false \
./gradlew bootRun
```

| 항목 | 값 | 왜 |
| --- | --- | --- |
| `ISSUE_LOG_LEVEL` | `WARN` | 건당 추적 로그가 응답 시간에 섞인다. 에러 로그는 유지된다 |
| `TOMCAT_MAX_THREADS` | `2000` | 동시 요청을 받아낼 워커 스레드 |
| `TOMCAT_MAX_CONNECTIONS` | `25000` | 동시에 열어둘 커넥션 |
| `TOMCAT_ACCEPT_COUNT` | `5000` | 대기 큐. 차면 연결이 거절된다 |
| **`DB_POOL_SIZE`** | **`100`** | **스레드만 올리면 안 된다 (아래)** |
| **`COUPON_RECONCILIATION_SCHEDULER_ENABLED`** | **`false`** | **켜두면 배치가 측정에 끼어든다 (아래)** |
| MySQL | `max_connections=500` | 풀 상한. 앱 외에 Outbox 발행기·스케줄러도 커넥션을 쓴다 |
| 부하 발생기 | `ulimit -n` 상향 | 셸 세션마다 재설정 필요 |
| 커널 | `ip_local_port_range` 확장 | 인스턴스 재시작 시 초기화됨 |

> ⚠️ **`DB_POOL_SIZE`를 빼먹으면 측정이 통째로 무너진다.** 스레드를 2,000으로 열어도 커넥션이 10개면 1,990개가 풀 앞에 줄만 선다. 실측으로 **200 VU에서 400건 전부 30초 타임아웃 후 500**이 났고, 풀만 100으로 올리자 500이 0건이 됐다(`integration-test-result.md` §1).

> ⚠️ **정합성 자동 스케줄러(#155)는 기본값이 켜짐이다.** 기동 30분 뒤부터 30분 간격으로 `ENDED` 쿠폰 전체를 순회하는데, SEED 쿠폰만 50만 건 × 6개라 **한 번에 6분 안팎**이 걸린다. 측정 구간에 이게 끼어들면 응답 시간과 커넥션 풀이 그 영향을 받아 결과를 믿을 수 없다.

**적정 풀 크기는 재서 정한다.** 1단계를 돌리면서 아래가 `0`을 유지하는 선까지 올린다.

```bash
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.pending
```

`pending`이 0보다 크면 스레드가 커넥션을 기다리는 중이고, 그 상태의 p95는 앱 성능이 아니라 **풀 크기를 재고 있는 값**이다.

## 3. 단계 구성

1~2단계는 기능이 도는지 확인하는 예비 단계이고, **3단계가 이번 테스트의 목표 규모**다. 4단계는 재고를 그대로 두고 요청만 늘려 경쟁률을 높인 보강 검증이다.

| 단계 | 재고 | 요청 | 경쟁률 | 반복 | 실행 명령 | 목적 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 1,000 | 2,000 | 2:1 | 3회 | `-e TOTAL_QUANTITY=1000 -e VUS=2000` | 기능 확인, 기준선 |
| 2 | 5,000 | 10,000 | 2:1 | 3회 | `-e TOTAL_QUANTITY=5000 -e VUS=10000` | 중간 규모 확인 |
| 3 | **10,000** | **20,000** | 2:1 | **5회** | `-e TOTAL_QUANTITY=10000 -e VUS=20000` | **최종 검증 — 프로젝트 목표 규모** |
| 4 | 10,000 | 50,000 | 5:1 | 3회 | `-e TOTAL_QUANTITY=10000 -e VUS=50000` | 극한 경쟁에서 정합성 보강 검증 (여유 시) |

전부 `-e SCENARIO=burst -e ITERATIONS_PER_VU=1` 을 함께 준다. **총 요청 수는 `VUS × ITERATIONS_PER_VU`** 인데, 목표가 "동시 사용자 N명"이므로 VU 를 N 개 두고 각각 한 번씩 쏜다. `2000 VU × 10회` 는 총 20,000건이긴 해도 동시 사용자는 2,000명뿐이라 선착순 상황이 재현되지 않는다.

3단계가 합격해야 프로젝트 요구사항을 충족한 것이다. 4단계는 같은 재고에 요청만 2.5배로 늘려 경쟁률을 5:1로 올린 조건으로, 3단계 통과 후에도 더 가혹한 상황에서 정합성이 유지되는지를 추가로 확인한다.

### 반복 규칙

- 첫 회차는 **워밍업**으로 간주하고 성능 평균에서 제외한다 (JIT 컴파일, 커넥션 풀 예열)
- **성능 지표**: 2~5회차 평균
- **정합성 지표**: 1회차 포함 **전 회차가 통과**해야 합격

동시성 결함은 확률적으로 발생하므로, 한 번 통과했다고 안전하다고 볼 수 없다.

## 4. 부하 프로파일

선착순 쿠폰은 오픈 시각에 요청이 한꺼번에 몰린다. 점진적 ramp-up이 아니라 **순간 스파이크**로 구성한다.

**스크립트는 이미 있다** — `load-test/k6/issue-coupon.js`. 여기 옵션을 새로 짜지 말고 환경변수로 조절한다. 실행 방법과 환경변수 전체 목록은 `load-test/README.md`에 있고, 이 문서는 **어떤 값으로 돌릴지**만 정한다.

### 시나리오 세 가지

`-e SCENARIO=` 로 고른다.

| 값 | 실행자 | 용도 |
| --- | --- | --- |
| `smoke` | `per-vu-iterations` (10 VU × 1) | 스크립트가 도는지만 확인. 기본값 |
| **`burst`** | `per-vu-iterations` (`VUS` × `ITERATIONS_PER_VU`) | **본 측정.** 모든 VU 가 동시에 출발해 선착순이 몰리는 순간을 재현한다 |
| `rate` | `constant-arrival-rate` (`RATE`/초) | 초당 요청 수를 고정해 처리량 한계를 본다 |

`shared-iterations` 가 아니라 **`per-vu-iterations`** 인 이유는 전자가 VU 들이 반복을 나눠 가지는 방식이라 출발 시점이 흩어지기 때문이다. 선착순은 "동시에 출발"이 핵심이다.

### 3단계 실행 예

```bash
k6 run \
  -e BASE_URL=http://<앱 사설 IP>:8080 \
  -e SCENARIO=burst \
  -e COUPON_ID=1 -e TOTAL_QUANTITY=10000 \
  -e VUS=20000 -e ITERATIONS_PER_VU=1 \
  -e MAX_DURATION=5m \
  load-test/k6/issue-coupon.js
```

`MAX_DURATION` 의 스크립트 기본값은 `10m` 이지만 **`5m` 으로 줄여서 준다.** §5.2 의 실패 판정이 "전체 실행 시간 5분 초과"라, 기본값으로 두면 6분에 끝난 회차를 k6 가 통과로 처리하고 사람이 따로 대조해야 한다. 5분으로 주면 k6 가 그 자리에서 강제 종료해 실패로 남긴다.

### 합격·불합격 판정

`thresholds` 는 스크립트가 들고 있고, k6 가 종료 코드로 판정해 준다. 문서에서 별도로 정의하지 않는다.

| 임계값 | 뜻 |
| --- | --- |
| `dropped_iterations: count==0` | 부하 발생기가 요청을 못 보내고 버린 건이 있으면 측정 자체가 무효다 |
| `issue_accept_rate`, `issue_conflict`, `issue_replayed` | 통합 테스트 전용 모드(`FIXED_IDEMPOTENCY_KEY` 등)를 켰을 때만 켜진다 |

**`http_req_failed` 로 정합성을 판정하면 안 된다.** 이 API 는 재고가 없어도 `202` 를 돌려주는 비동기 구조라(§5.2), HTTP 실패율은 "발급이 잘 됐는가"와 무관하다. 정합성은 반드시 §5.1 의 SQL 로 본다.

> 여러 대에서 나눠 쏠 때는 `INSTANCE_INDEX` · `INSTANCE_STRIDE` 로 기기마다 쓸 회원 구간을 갈라야 한다. 겹치면 뒤 기기 요청이 전부 중복 발급으로 튕긴다 — 방법은 `README.md` "여러 대에서 나눠 쏠 때" 참고.

## 5. 측정 지표

### 5.1 정합성 (최우선)

부하 종료 후 **검증 스크립트 두 개**로 확인한다. 여기 쿼리를 새로 짜지 않는다 — 판정 기준이 스크립트 안에 주석과 함께 들어 있어서, 문서에 복사해두면 둘이 갈라진다.

```bash
docker cp load-test/sql/verify_issue_result.sql petcoupon-mysql:/tmp/
docker exec petcoupon-mysql mysql -uroot -proot --default-character-set=utf8mb4 petcoupon \
  -e "source /tmp/verify_issue_result.sql"
```

`--default-character-set=utf8mb4` 를 빼면 한글 별칭이 깨져 문법 오류로 끝난다.

두 스크립트 모두 대상 쿠폰이 1번이 아니면 파일 앞의 `SET @coupon_id` 를 바꾼다.

| 스크립트 | 보는 것 |
| --- | --- |
| `verify_issue_result.sql` | 발급 건수·재고 정합성·중복 발급·순번 무결성·멱등키 상태 — **PASS/FAIL 로 판정** |
| `verify_order_inversion.sql` | 선착순 순서 역전율(기록용) + 순번 1..N 무결성(판정용) |

### 합격 조건

| 검증 항목 | 합격 조건 |
| --- | --- |
| 발급 건수 | **`MIN(고유 신청자 수, 총재고)`** — 아래 참고 |
| 재고 정합성 | Redis 잔여 + DB 발급 수 = 총재고 |
| 중복 발급 | 0건 |
| 순번 정합성 | 1~N 이 빠짐·중복 없이 한 번씩 |
| 멱등성 | 같은 Idempotency-Key 재전송 시 발급 1건 |
| 응답 ↔ DB 일치 | k6 접수 건수 vs DB 건수 |

> **발급 건수를 "정확히 재고 수량"으로 보면 안 된다.** 이 시스템은 1인 1매라, 고유 신청자가 재고보다 적으면 재고가 남는 게 정상이다. 경쟁률 2:1 인 3단계에서는 결과적으로 재고와 같아지지만, 판정식 자체는 `MIN(고유 신청자, 총재고)` 여야 한다. `verify_issue_result.sql` 1번 항목이 이 식으로 되어 있다.

### 꼬리 유실까지 보려면

`verify_order_inversion.sql` 의 `@expected_issued_count` 를 비워두면 **DB 에 있는 것끼리만** 1..N 을 본다. 그러면 중간이 빈 것은 잡지만 **마지막 몇 건이 통째로 유실된 것은 못 잡는다** — 1..100 중 100번만 없으면 남은 1..99 가 그 자체로 온전해 보이기 때문이다.

Lua 가 실제로 몇 번까지 내줬는지는 Redis 에만 있으므로, 값을 읽어서 채운다.

```bash
docker exec petcoupon-redis redis-cli GET "coupon:issue:sequence:{1}"
```

`verify_issue_result.sql` 1번 항목도 같은 유실을 잡으므로 이 값은 선택이지만, **본 측정(3단계)에서는 채우는 것을 권한다.**

### 5.2 성능

접수와 확정이 분리된 구조이므로, **k6가 측정하는 것은 접수 시간뿐**이다.

```
사용자 요청 ──▶ 202 WAITING          ← k6가 재는 구간
                    │
                    ▼ (비동기)
              Consumer가 DB 저장     ← k6가 보지 못하는 구간
```

| 지표 | 측정 방법 | 목표 | 실패 판정 |
| --- | --- | --- | --- |
| 접수 응답 p95 | k6 | 500ms 이내 | 3초 초과 |
| 접수 응답 p99 | k6 | 1초 이내 | |
| 처리량 (TPS) | k6 | 기록 | |
| 에러율 | k6 | 1% 미만 | |
| 타임아웃 | k6 | **0건** | 1건이라도 발생 |
| **전건 확정 소요** | **DB 폴링** | 기록 | 5분 내 미완료 |
| 최대 Consumer Lag | Kafka CLI | 기록 | 0으로 수렴하지 않음 |
| 전체 실행 시간 | k6 `maxDuration` | 5분 이내 | 초과 |

이전 실험의 p95 3초 기준은 **동기 DB 저장을 포함한 구조**의 값이므로, 현재 구조에서는 비교용 참고치로만 사용한다.

### 5.3 시스템 자원

| 출처 | 지표 |
| --- | --- |
| Actuator | `hikaricp.connections.active`, `hikaricp.connections.pending`, `jvm.memory.used`, `tomcat.threads.busy` |
| Redis | `INFO commandstats`, CPU 사용률 |
| EC2 | CPU, 메모리, load average |

## 6. 확정 지연 측정

부하 종료 직후부터 1초 간격으로 DB 건수를 세어, 목표치에 도달하는 시각을 기록한다.

```bash
./load-test/scripts/measure-confirm-delay.sh <couponId> <목표건수> [출력파일]
```

```bash
# 재고 10,000 단계, 3단계 2회차
./load-test/scripts/measure-confirm-delay.sh 1 10000 stage3-run2.csv
```

결과는 CSV(`elapsed_sec,issued_count`)로 남는다. 그래프로 그리면 **확정 곡선**이 된다.

```
0s   →  2,341건
1s   →  5,102건
2s   →  7,880건
3s   → 10,000건   ← 전건 확정
```

기본 타임아웃은 300초다. 이를 넘기면 미확정 건수를 출력하고 종료하며, **이 값이 곧 유실 의심 건수**다. Consumer가 중단된 경우를 대비해 타임아웃은 반드시 유지한다.

접속 정보는 환경변수로 덮어쓸 수 있다(`MYSQL_CONTAINER`, `MYSQL_USER`, `MYSQL_PASSWORD`, `MYSQL_DATABASE`, `TIMEOUT_SEC`).

### Consumer Lag 확인

폴링이 "얼마나 걸렸는지"를 알려준다면, Lag은 **어디서 밀렸는지**를 알려준다.

```bash
docker exec petcoupon-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group petcoupon
```

```
TOPIC                PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
coupon-issue-events  0          3200            10000           6800
```

부하 중 **최대 Lag**과 **0으로 수렴하는 시각**을 기록한다.

## 7. 실행 절차

**0. 앱 기동** (회차마다 다시 띄우지 않는다) — §2 사전 설정의 환경변수를 전부 지정한다. `DB_POOL_SIZE` 와 `COUPON_RECONCILIATION_SCHEDULER_ENABLED=false` 를 빠뜨리지 않는다.

기동 직후 컴포넌트 상태를 한 번 확인한다(#170).

```bash
curl -s -H "X-ADMIN-KEY: <토큰>" http://localhost:8080/admin/system/health
```

`db` · `redis` 가 `UP` 이어야 한다. **Kafka 는 이 API 에 안 나온다**(헬스 인디케이터 미구현) — Consumer Lag 으로 따로 본다(§6).

---

각 회차마다 아래를 반복한다.

1. **초기화**
   ```bash
   curl -X POST http://localhost:8080/internal/coupons/1/reset \
     -H "Content-Type: application/json" -d '{"totalQuantity": 10000}'
   ```
   발급·이력·멱등키·Outbox 를 지우고 Redis 키(`stock`, `applicants`, `sequence`, `request-sequence`)도 비운다. 응답의 `redisStock` 이 `totalQuantity` 와 같은지 확인하고 다음으로 넘어간다.

   > ⚠️ **발급 이력이 쌓여 있으면 오래 걸린다.** 발급 50만 + 이력 65만 건 삭제에 **470초(약 8분)** 가 걸렸다(TC-83 실측). 회차 간 대기 시간을 일정에 넣어야 한다 — 5회차면 초기화에만 40분이다. 부하 테스트용 쿠폰은 회차마다 2만 건 수준이라 훨씬 빠르지만, 300만 더미데이터가 같은 DB 에 있으면 삭제 대상 조회가 느려질 수 있다.

2. **부하 실행** — k6 (별도 인스턴스, §4)

3. **확정 지연 측정** — 부하 종료 직후 폴링 스크립트 실행 (§6)

4. **정합성 검증** — §5.1 의 검증 스크립트 실행

5. **결과 기록** — §8 표에 기입

### 측정 중에 볼 것

| 창구 | 보는 것 |
| --- | --- |
| `/actuator/metrics/hikaricp.connections.pending` | **0 유지** — 0보다 크면 풀 크기를 재고 있는 것이다 |
| `/actuator/metrics/tomcat.threads.busy` | 워커 스레드 포화 여부 |
| `GET /admin/coupon-issue/statistics` (#156) | 시간대별 처리량과 메시지 상태 분포. `inProgressCount` 가 줄어드는 속도로 밀림을 본다 |
| `GET /admin/dashboard/summary` (#172) | 발급률·재고 합계 |
| Kafka Consumer Lag | 최대값과 0 수렴 시각 (§6) |

`/admin/**` 은 전부 `X-ADMIN-KEY` 가 필요하다.

### 시각화용 회차는 따로 돈다

발표용 선착순 시각화는 로그를 파싱하는데, **로그를 쌓으면서 재면 그 I/O 가 측정값에 섞인다.** 그래서 회차를 나눈다.

| 회차 | 설정 | 용도 |
| --- | --- | --- |
| 성능 측정 | `ISSUE_LOG_LEVEL=WARN`, `LOG_FILE` 없음 | §8 성능 표에 기록 |
| 시각화 | `ISSUE_LOG_LEVEL=INFO`, `LOG_FILE=logs/petcoupon.log` | **성능 수치로 쓰지 않는다** |

```bash
ISSUE_LOG_LEVEL=INFO LOG_FILE=logs/petcoupon.log ... ./gradlew bootRun
```

## 8. 결과 기록표

### 성능

| 단계 | 회차 | TPS | p95(ms) | p99(ms) | 에러율 | 타임아웃 | 확정 소요(s) | 최대 Lag |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 3 | 1 (워밍업) | | | | | | | |
| 3 | 2 | | | | | | | |
| 3 | 3 | | | | | | | |
| 3 | 4 | | | | | | | |
| 3 | 5 | | | | | | | |
| 3 | **평균(2~5)** | | | | | | | |

### 정합성

| 단계 | 회차 | 발급 건수 | 재고 합계 | 중복 발급 | 순번 중복·누락 | 판정 |
| --- | --- | --- | --- | --- | --- | --- |
| 3 | 1 | | | | | |
| 3 | 2 | | | | | |
| … | | | | | | |

### 이전 실험과 비교 (3단계 기준)

| 지표 | 이전 실험 | 이번 측정 | 비고 |
| --- | --- | --- | --- |
| TPS | | | |
| p95 | | | 구조가 달라 참고치 |
| 재고 정합성 | 6/6 유지 | | |
| HikariCP 활성 커넥션 | 미측정 | | 이번에 추가 |
| Consumer Lag | 미측정 | | 이번에 추가 |

## 9. 선행 조건

부하 테스트는 통합 테스트 통과 후에 실행한다. **통합 테스트는 80건 전건 통과했다**(`integration-test-result.md`).

| 항목 | 상태 | 근거 |
| --- | --- | --- |
| 발급 API ↔ Redis Lua ↔ Kafka 배선 | ✅ | 통합 테스트 80/80 |
| 초기화 API의 Redis 키 삭제 | ✅ | TC-55 — 삭제 건수·`redisStock` 대조 |
| Redis 재고 초기화 로직 | ✅ | TC-55 · TC-17 |
| Redis 재고 보상(restore) | ✅ | TC-95 — abandon 시 `RESTORED` |
| Tomcat 튜닝 · Actuator 노출 | ✅ | `application.properties` (전부 환경변수) |
| 발급 이력 대량 더미데이터 | ✅ | 300만 건 적재 (§7 · `integration-test-result.md` §5) |
| **AWS 인스턴스 3대 구성** | ❌ | **남은 유일한 블로커** — 사양·네트워크·OS 설정은 별도 이슈에서 확정한다 |

착수 판정과 근거는 `integration-test-result.md` §6에 있다.
