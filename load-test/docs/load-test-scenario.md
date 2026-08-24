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
[EC2 B] MySQL 8.0 + Redis 7.2 (Docker Compose)
```

| 항목 | 이유 |
| --- | --- |
| k6를 별도 인스턴스로 분리 | 앱 서버에서 돌리면 부하 발생기가 CPU를 점유해 측정이 왜곡된다 |
| 앱과 DB·Redis를 분리 | 한 대에 올리면 병목이 앱인지 DB인지 구분되지 않는다 |
| 같은 VPC 안에서 실행 | 인터넷 왕복 지연이 응답 시간에 섞이지 않는다 |
| RDS·ElastiCache 대신 EC2 + Compose | 로컬과 동일한 `docker-compose.yml`을 그대로 사용해 재현성을 확보한다 |

### 로컬 PC에서 부하를 발생시키지 않는 이유

| 제약 | 내용 |
| --- | --- |
| TCP 포트 | Windows 기본 동적 포트가 16,384개(49152~65535). 연결 하나당 포트 하나를 쓰므로 동시 50,000 연결이 불가능하다. 닫힌 연결도 `TIME_WAIT`으로 약 2분간 포트를 점유한다 |
| 네트워크 지연 | 집 → AWS 왕복이 10~30ms. 같은 VPC 내부는 1ms 미만. 응답 시간에 인터넷 변수가 섞여 재현이 되지 않는다 |

소규모 통합 테스트(200 요청 수준)는 로컬에서 실행해도 무방하다.

### 사전 설정

| 항목 | 값 |
| --- | --- |
| Tomcat | `threads.max=2000`, `max-connections=25000`, `accept-count=5000` |
| MySQL | `max_connections=500` |
| 부하 발생기 | `ulimit -n` 상향 (셸 세션마다 재설정 필요) |
| 커널 | `ip_local_port_range` 확장 (인스턴스 재시작 시 초기화됨) |

## 3. 단계 구성

재고는 10,000으로 고정하고 요청 수를 늘려 **경쟁률**을 높인다. 경쟁이 심할수록 동시성 결함이 드러날 확률이 올라간다.

| 단계 | 재고 | 요청 | 경쟁률 | 반복 | 목적 |
| --- | --- | --- | --- | --- | --- |
| 1 | 1,000 | 2,000 | 2:1 | 3회 | 기능 확인, 기준선 |
| 2 | 5,000 | 10,000 | 2:1 | 3회 | 중간 규모 확인 |
| 3 | **10,000** | **20,000** | 2:1 | **5회** | **최종 검증 — 이전 실험과 동일 조건** |
| 4 | **10,000** | **50,000** | **5:1** | **5회** | **극한 경쟁에서 정합성 검증** |
| 5 | 10,000 | 20,000 | 2:1 | 3회 | Tomcat 튜닝 전 (비교용, 선택) |

3단계는 이전 실험 결과와 나란히 놓기 위한 기준이고, 4단계가 이번 테스트의 핵심이다.

### 반복 규칙

- 첫 회차는 **워밍업**으로 간주하고 성능 평균에서 제외한다 (JIT 컴파일, 커넥션 풀 예열)
- **성능 지표**: 2~5회차 평균
- **정합성 지표**: 1회차 포함 **전 회차가 통과**해야 합격

동시성 결함은 확률적으로 발생하므로, 한 번 통과했다고 안전하다고 볼 수 없다.

## 4. 부하 프로파일

선착순 쿠폰은 오픈 시각에 요청이 한꺼번에 몰린다. 점진적 ramp-up이 아니라 **순간 스파이크**로 구성한다.

```js
export const options = {
  scenarios: {
    burst: {
      executor: 'shared-iterations',
      vus: 20000,
      iterations: 50000,
      maxDuration: '5m',        // 초과 시 강제 종료 = 실패
    },
  },
  thresholds: {
    'http_req_duration{expected_response:true}': ['p(95)<500'],
    'http_req_failed': ['rate<0.01'],
  },
};
```

`thresholds`를 설정하면 k6가 자동으로 합격·불합격을 판정한다.

## 5. 측정 지표

### 5.1 정합성 (최우선)

부하 종료 후 SQL로 확인한다.

| 검증 항목 | 확인 방법 | 합격 조건 |
| --- | --- | --- |
| 초과 발급 | `SELECT COUNT(*) FROM coupon_issue WHERE coupon_id = ?` | 정확히 재고 수량 |
| 재고 정합성 | Redis 잔여 + DB 발급 수 | = 총 재고 |
| 중복 발급 | `GROUP BY user_id HAVING COUNT(*) > 1` | 0건 |
| 순번 정합성 | `sequence_no` 분포 | 1~N이 빠짐·중복 없이 한 번씩 |
| 멱등성 | 같은 Idempotency-Key 재전송 | 발급 1건만 |
| 응답 ↔ DB 일치 | k6 성공 응답 수 vs DB 건수 | 일치 |

**순번 검증 쿼리**

```sql
SELECT COUNT(*)                  AS total,
       COUNT(DISTINCT sequence_no) AS distinct_no,
       MIN(sequence_no)          AS min_no,
       MAX(sequence_no)          AS max_no
  FROM coupon_issue
 WHERE coupon_id = ?;
-- 재고 10,000인 경우 10000, 10000, 1, 10000 이어야 정상
```

**중복 발급 검증 쿼리**

```sql
SELECT user_id, COUNT(*)
  FROM coupon_issue
 WHERE coupon_id = ?
 GROUP BY user_id
HAVING COUNT(*) > 1;
-- 결과가 비어 있어야 정상
```

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

각 회차마다 아래 순서를 반복한다.

1. **초기화**
   ```
   POST /internal/coupons/{couponId}/reset
   ```
   Redis 키(`stock`, `applicants`, `sequence`, `request-sequence`)도 함께 비운다.

2. **애플리케이션 기동** — Tomcat 튜닝값을 환경변수로 지정

3. **부하 실행** — k6 (별도 인스턴스)

4. **확정 지연 측정** — 부하 종료 직후 폴링 스크립트 실행

5. **정합성 검증** — §5.1의 SQL 실행

6. **결과 기록** — §8 표에 기입

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

부하 테스트는 통합 테스트 통과 후에 실행한다. 아래가 완료되어야 한다.

| 항목 | 상태 |
| --- | --- |
| 발급 API ↔ Redis Lua ↔ Kafka 배선 | 진행 중 |
| 초기화 API의 Redis 키 삭제 | 미구현 — 2회차부터 전건 실패 |
| Redis 재고 초기화 로직 | 미구현 |
| Redis 재고 보상(restore) | 미구현 |
| Tomcat 튜닝 · Actuator 노출 | 미적용 |
| 발급 이력 300만 더미데이터 | 미작성 |
| AWS 인스턴스 3대 구성 | 미구성 |
