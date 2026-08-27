-- =====================================================================
-- 선착순 발급 부하 테스트 결과 정합성 검증
--
-- k6 응답만으로는 당첨 여부를 알 수 없다(모든 응답이 WAITING).
-- 재고를 넘겨 발급되지 않았는지, 1인 1매가 지켜졌는지, 순번이 온전한지는
-- 부하가 끝난 뒤 이 쿼리로 확인한다.
--
-- 실행
--   docker cp load-test/sql/verify_issue_result.sql petcoupon-mysql:/tmp/
--   docker exec petcoupon-mysql mysql -uroot -proot --default-character-set=utf8mb4 petcoupon -e "source /tmp/verify_issue_result.sql"
--
-- --default-character-set=utf8mb4 를 빼면 안 된다. mysql 클라이언트가 컨테이너 로케일을 따라
-- latin1 로 읽어서 한글 컬럼 별칭이 깨지고 "You have an error in your SQL syntax" 로 끝난다.
--
-- 대상 쿠폰이 1번이 아니면 아래 SET 을 바꾼다.
-- =====================================================================

SET @coupon_id = 1;

-- ---------------------------------------------------------------------
-- 0. 확정이 끝났는지 먼저 본다
--
--    발급 확정은 Outbox -> Kafka -> Consumer 로 이어지는 비동기 경로다.
--    부하 종료 직후에는 아직 밀려 있을 수 있고, 그 상태로 아래 검증을 읽으면
--    "발급 건수가 재고보다 적다"는 잘못된 FAIL 이 나온다.
--
--    SENT 는 Kafka 발행까지만 끝난 상태다. DB 저장이 끝나야 CONSUMED 가 되므로
--    (CouponIssuePersister.markConsumed), 둘을 묶어서 보면 아직 처리 중인 건을 놓친다.
--    대기 · 재시도대기 · 발행중 이 모두 0 이 될 때까지 이 블록만 반복 실행한 뒤 1번으로 내려간다.
--
--    Outbox 만으로는 부족하다. Outbox 에 행이 생기는 건 Stream Consumer 가 판정을 끝낸 뒤라,
--    아직 Redis Stream 에 남아 있는 요청은 이 표에 아예 나타나지 않는다. 아래도 함께 0 이어야 한다.
--
--      docker exec petcoupon-redis redis-cli XINFO GROUPS coupon:issue:stream
--        lag     아직 아무도 안 읽어간 메시지 수
--        pending 읽어갔지만 ACK 안 된 메시지 수 (처리 실패로 남은 것)
-- ---------------------------------------------------------------------
SELECT '0. 처리 대기 중인 Outbox' AS 확인,
	   COALESCE(SUM(status = 'PENDING'), 0)                    AS 대기,
	   COALESCE(SUM(status = 'FAILED'), 0)                     AS 재시도대기,
	   COALESCE(SUM(status = 'DLQ'), 0)                        AS 처리포기,
	   COALESCE(SUM(status = 'SENT'), 0)                       AS 발행중,
	   COALESCE(SUM(status = 'CONSUMED'), 0)                   AS 확정완료
  FROM issue_message
 WHERE coupon_id = @coupon_id;

-- coupon_id를 잘못 지정했을 때 아래 CROSS JOIN 항목이 조용히 사라지는 것을 막는다.
SELECT '0-1. 검증 대상 쿠폰/재고 존재' AS 확인,
	   CAST(@coupon_id AS CHAR) AS coupon_id,
	   IF(EXISTS(SELECT 1 FROM coupon WHERE coupon_id = @coupon_id)
		  AND EXISTS(SELECT 1 FROM coupon_stock WHERE coupon_id = @coupon_id), 'PASS', 'FAIL') AS 결과;

-- ---------------------------------------------------------------------
-- 1. 본 검증 — 전부 PASS 여야 한다
-- ---------------------------------------------------------------------
-- 재고보다 적게 쏜 회차에서는 발급 건수도 그만큼만 나오는 게 정상이다. 총재고를 그대로
-- 기대값으로 두면 그런 회차가 전부 FAIL 로 나온다. 접수된 요청 수와 총재고 중 작은 쪽을
-- 기대값으로 잡아 과발급 회차와 소량 회차를 같은 쿼리로 본다.
-- 접수 수는 idempotency_key 행 수로 센다 — 초기화 API 가 쿠폰별로 지우므로 이번 회차 값만 남는다.
--
-- 세는 기준은 "202 를 돌려받았는가" 다. 접수 자체가 500 으로 끝난 건만 빼고 전부 센다.
--   begin() 이 행을 먼저 만들고 그 뒤에 결과가 갈리므로 조건 없이 세면 Redis 순단 등으로
--   500 이 난 건까지 접수로 잡혀 기대값만 부풀고 없는 재고 유실처럼 FAIL 이 난다.
--   그 건들은 failWithoutBody 로 기록돼 status = FAILED 이면서 response_status 가 NULL 이다.
--
-- SUCCEEDED 로 좁히면 안 된다. 접수는 됐지만 발급이 안 된 건(SOLD_OUT · 중복 · DLQ 확정)이
-- FAILED 로 바뀌는데, 그러면 발급이 유실될수록 기대값도 같이 줄어 미달 발급을 못 잡는다.
-- 재고 10,000 중 1,000 건이 DLQ 로 빠져 9,000 만 발급돼도 기대 9,000 · 실제 9,000 이 되어
-- PASS 로 나온다. 접수 기준으로 세야 기대 10,000 · 실제 9,000 으로 FAIL 이 뜬다.
--
-- response_status 로 202 만 거르는 것도 안 된다 — 발급이 확정되면 CouponIssuePersister 가
-- succeed(recordId, 200, ...) 으로 덮어써서 정상 발급 건이 통째로 빠진다.
SELECT '1. 발급 건수 = MIN(접수 요청, 총재고)' AS 검증항목,
       CAST(LEAST(r.accepted, s.total_quantity) AS CHAR) AS 기대,
       CAST(i.cnt AS CHAR)                               AS 실제,
       IF(i.cnt = LEAST(r.accepted, s.total_quantity), 'PASS', 'FAIL') AS 결과
  FROM (SELECT total_quantity FROM coupon_stock WHERE coupon_id = @coupon_id) s
 CROSS JOIN (SELECT COUNT(*) AS cnt FROM coupon_issue WHERE coupon_id = @coupon_id) i
 CROSS JOIN (SELECT COUNT(*) AS accepted FROM idempotency_key
              WHERE coupon_id = @coupon_id
                AND NOT (status = 'FAILED' AND response_status IS NULL)) r

UNION ALL
-- uk_issue_coupon_user 가 막아주지만, 제약이 빠졌을 때를 대비해 실제 데이터로도 본다.
SELECT '2. 1인 2매 이상 발급', '0',
       CAST(COUNT(*) AS CHAR), IF(COUNT(*) = 0, 'PASS', 'FAIL')
  FROM (SELECT user_id
          FROM coupon_issue
         WHERE coupon_id = @coupon_id
         GROUP BY user_id
        HAVING COUNT(*) > 1) d

UNION ALL
SELECT '3. 순번 중복', '0',
       CAST(COUNT(*) AS CHAR), IF(COUNT(*) = 0, 'PASS', 'FAIL')
  FROM (SELECT sequence_no
          FROM coupon_issue
         WHERE coupon_id = @coupon_id
         GROUP BY sequence_no
        HAVING COUNT(*) > 1) d

UNION ALL
-- 순번은 Redis 가 매긴다. 1..N 이 끊기면 판정은 났는데 DB 확정이 빠진 건이 있다는 뜻이다.
--
-- 발급이 0 건이면 MIN·MAX 가 NULL 이고 NULL = 1 은 참도 거짓도 아닌 NULL 이라 IF 가 FAIL 로 떨어진다.
-- 초기화 직후처럼 아직 아무것도 안 쏜 상태에서 없는 순번 버그를 있는 것처럼 보고하게 되므로
-- 0 건은 검사 대상이 아닌 것으로 본다. "발급이 나왔어야 하는데 0 건인가" 는 1 번이 판정한다.
SELECT '4. 순번 연속(1..N)',
       CONCAT('1..', CAST(COUNT(*) AS CHAR)),
       CONCAT(CAST(IFNULL(MIN(sequence_no), 0) AS CHAR), '..',
              CAST(IFNULL(MAX(sequence_no), 0) AS CHAR)),
       IF(COUNT(*) = 0
          OR (MIN(sequence_no) = 1 AND MAX(sequence_no) = COUNT(*)), 'PASS', 'FAIL')
  FROM coupon_issue
 WHERE coupon_id = @coupon_id

UNION ALL
SELECT '5. 쿠폰코드 중복', '0',
       CAST(COUNT(*) AS CHAR), IF(COUNT(*) = 0, 'PASS', 'FAIL')
  FROM (SELECT coupon_code
          FROM coupon_issue
         WHERE coupon_id = @coupon_id
         GROUP BY coupon_code
        HAVING COUNT(*) > 1) d

UNION ALL
SELECT '6. request_id 중복', '0',
       CAST(COUNT(*) AS CHAR), IF(COUNT(*) = 0, 'PASS', 'FAIL')
  FROM (SELECT request_id
          FROM coupon_issue
         WHERE coupon_id = @coupon_id
         GROUP BY request_id
        HAVING COUNT(*) > 1) d

UNION ALL
-- 발급은 됐는데 이력이 안 남은 경우. LEFT JOIN 이라야 "이력 자체가 없는 건"이 잡힌다.
SELECT '7. 이력 없는 발급', '0',
       CAST(COUNT(*) AS CHAR), IF(COUNT(*) = 0, 'PASS', 'FAIL')
  FROM coupon_issue ci
  LEFT JOIN coupon_issue_history h ON h.coupon_issue_id = ci.coupon_issue_id
 WHERE ci.coupon_id = @coupon_id
   AND h.history_id IS NULL

UNION ALL
-- 현재 상태와 마지막 이력의 도착 상태가 어긋나면, 상태만 바뀌고 이력이 안 쌓인 것이다.
SELECT '8. 상태 ↔ 최신이력 불일치', '0',
       CAST(COUNT(*) AS CHAR), IF(COUNT(*) = 0, 'PASS', 'FAIL')
  FROM coupon_issue ci
  JOIN (SELECT h.coupon_issue_id, h.to_status
          FROM coupon_issue_history h
          JOIN (SELECT coupon_issue_id, MAX(history_id) AS last_id
                  FROM coupon_issue_history
                 WHERE coupon_id = @coupon_id
                 GROUP BY coupon_issue_id) last
            ON last.last_id = h.history_id) t
    ON t.coupon_issue_id = ci.coupon_issue_id
 WHERE ci.coupon_id = @coupon_id
   AND ci.status <> t.to_status

UNION ALL
-- Outbox 는 발급 한 건당 한 건이다. 적으면 유실, 많으면 중복 발행이다.
SELECT '9. Outbox 건수 = 발급 건수',
       CAST(i.cnt AS CHAR), CAST(m.cnt AS CHAR),
       IF(m.cnt = i.cnt, 'PASS', 'FAIL')
  FROM (SELECT COUNT(*) AS cnt FROM coupon_issue  WHERE coupon_id = @coupon_id) i
 CROSS JOIN (SELECT COUNT(*) AS cnt FROM issue_message WHERE coupon_id = @coupon_id) m

UNION ALL
-- SENT 를 반드시 포함한다. 0 번과 같은 기준이어야 한다 — SENT 는 Kafka 발행까지만 끝나고
-- DB 확정(CONSUMED)은 아직인 상태다. 빼면 0 번 출력을 놓친 사람이 아직 처리 중인 데이터를
-- 완료된 것으로 읽고, 그 상태의 발급 건수·순번을 그대로 판정하게 된다.
SELECT '10. 미처리 Outbox', '0',
       CAST(COUNT(*) AS CHAR), IF(COUNT(*) = 0, 'PASS', 'FAIL')
  FROM issue_message
 WHERE coupon_id = @coupon_id
   AND status IN ('PENDING', 'FAILED', 'SENT', 'DLQ')

UNION ALL
-- Consumer 가 발급을 저장하면서 같이 깎는 값이다. 발급 건수와 어긋나면 둘 중 하나가 새고 있다.
SELECT '11. 재고 카운터 = 발급 건수',
       CAST(i.cnt AS CHAR), CAST(s.issued_quantity AS CHAR),
       IF(s.issued_quantity = i.cnt, 'PASS', 'FAIL')
  FROM (SELECT issued_quantity FROM coupon_stock WHERE coupon_id = @coupon_id) s
 CROSS JOIN (SELECT COUNT(*) AS cnt FROM coupon_issue WHERE coupon_id = @coupon_id) i

UNION ALL
SELECT '12. 잔여재고 = 총재고 - 발급',
       CAST(s.total_quantity - s.issued_quantity AS CHAR),
       CAST(s.remaining_quantity AS CHAR),
       IF(s.remaining_quantity = s.total_quantity - s.issued_quantity, 'PASS', 'FAIL')
  FROM coupon_stock s
 WHERE s.coupon_id = @coupon_id

UNION ALL
-- 끝나지 않은 멱등키. 남아 있으면 응답을 못 받고 끊긴 요청이 있었다는 뜻이다.
SELECT '13. IN_PROGRESS 멱등키', '0',
       CAST(COUNT(*) AS CHAR), IF(COUNT(*) = 0, 'PASS', 'FAIL')
  FROM idempotency_key
 WHERE coupon_id = @coupon_id
   AND status = 'IN_PROGRESS';

-- ---------------------------------------------------------------------
-- 2. 참고 지표 (PASS/FAIL 이 아니라 수치로 읽는다)
-- ---------------------------------------------------------------------
SELECT '접수된 요청 수(멱등키)' AS 항목, COUNT(*) AS 값
  FROM idempotency_key WHERE coupon_id = @coupon_id
UNION ALL
SELECT '발급 확정 수', COUNT(*) FROM coupon_issue WHERE coupon_id = @coupon_id
UNION ALL
SELECT '탈락 수(접수 - 확정)',
       (SELECT COUNT(*) FROM idempotency_key WHERE coupon_id = @coupon_id)
     - (SELECT COUNT(*) FROM coupon_issue    WHERE coupon_id = @coupon_id)
UNION ALL
-- 첫 발급부터 마지막 발급까지 걸린 시간. 확정 처리량(건/초)을 가늠하는 값이다.
SELECT '확정 소요(초)',
       TIMESTAMPDIFF(SECOND, MIN(created_at), MAX(created_at))
  FROM coupon_issue WHERE coupon_id = @coupon_id;

-- ---------------------------------------------------------------------
-- 3. FAIL 이 났을 때 실제 데이터 들여다보기 (필요할 때 주석 해제)
-- ---------------------------------------------------------------------
-- 순번이 끊긴 구간 찾기 (LEAD 로 바로 다음 순번과 비교한다)
-- SELECT seq + 1 AS 빠진순번_시작, next_seq - 1 AS 빠진순번_끝
--   FROM (SELECT sequence_no AS seq,
--                LEAD(sequence_no) OVER (ORDER BY sequence_no) AS next_seq
--           FROM coupon_issue WHERE coupon_id = @coupon_id) t
--  WHERE next_seq > seq + 1
--  LIMIT 20;
--
-- 2매 이상 받은 회원
-- SELECT user_id, COUNT(*) AS 발급수
--   FROM coupon_issue WHERE coupon_id = @coupon_id
--  GROUP BY user_id HAVING COUNT(*) > 1 LIMIT 20;
--
-- 실패한 Outbox 의 사유
-- SELECT message_id, sequence_no, status, retry_count, last_error
--   FROM issue_message
--  WHERE coupon_id = @coupon_id AND status IN ('FAILED', 'DLQ')
--  LIMIT 20;
