-- =====================================================================
-- TC-91 · TC-93 선착순 순서 역전율
--
-- "몇 건 발급됐는가"가 아니라 "먼저 온 사람이 먼저 받았는가"를 본다.
--
-- 실행
--   docker cp load-test/sql/verify_order_inversion.sql petcoupon-mysql:/tmp/
--   docker exec petcoupon-mysql mysql -uroot -proot --default-character-set=utf8mb4 petcoupon -e "source /tmp/verify_order_inversion.sql"
--
--   --default-character-set=utf8mb4 를 빼면 한글 별칭이 깨져 문법 오류로 끝난다.
--
-- 대상 쿠폰이 1번이 아니면 아래 SET 을 바꾼다.
-- =====================================================================
--
-- 앞의 순서를 어디서 얻는가 — 그리고 그게 "도착 순서"가 아닌 이유
-- ---------------------------------------------------------------------
--   시나리오 문서는 "서버 도착 로그"라고 적고 있지만, 컨트롤러에는 진입 로그가 없다.
--   대신 request_id 를 쓴다.
--
--     request_id = 'issue:{idempotency_id}'
--
--   다만 이 값은 HTTP 도착 순서가 아니다. idempotency_id 는 begin() 처리 중
--   idempotency_key INSERT 가 실행되어 AUTO_INCREMENT 를 할당받은 순서다.
--   HTTP 가 먼저 도착한 요청이라도 그 사이에
--
--     톰캣 스레드 배정 -> 쿠폰 존재 확인 -> 회원 존재 확인 -> begin() INSERT
--
--   를 거치면서 순서가 뒤집힐 수 있다(중간에 MySQL 왕복이 두 번 있다).
--
--   그래서 이 쿼리는 요청 도착 순서에 대한 공정성을 증명하지 않는다.
--   멱등키 등록 순서와 Lua sequence_no 사이의 역전 정도를 참고용으로 잴 뿐이다.
--
-- 역전율은 얼마여야 하는가
-- ---------------------------------------------------------------------
--   기준값이 없다. 위 이유로 앞의 순서 자체가 확정적이지 않고, 동시 요청에서는
--   완전 일치를 기대하지 않는다(시나리오 문서 G 구간 참고). 판정용이 아니라 기록용이다.
--
-- 무엇이 판정 대상인가
-- ---------------------------------------------------------------------
--   아래 "순번 1..N 무결성" 하나다. Lua 가 내준 순번이 DB 에 빠짐없이 반영됐는지 본다.
--
--   @expected_issued_count 를 비워두면 DB 에 있는 것끼리만 본다. 그러면 중간이 빈 것은
--   잡지만 꼬리가 잘린 것은 못 잡는다 — 1..100 중 100 번만 유실되면 남은 1..99 가
--   그 자체로 온전해 보이기 때문이다. Lua 가 몇 번까지 내줬는지는 Redis 에만 있어서
--   SQL 이 스스로 알 수 없다.
--
--   그 경우도 verify_issue_result.sql 1번 항목이 "발급 건수 = MIN(접수 요청, 총재고)" 로
--   잡아내므로, 이 값은 선택이다. 더 엄밀히 보려면 아래 SET 에 채운다.
-- =====================================================================

SET @coupon_id = 1;

-- Lua 가 실제로 내준 마지막 순번. 채우면 꼬리 유실까지 잡는다. 비워두면 이 검사를 건너뛴다.
--   docker exec petcoupon-redis redis-cli GET "coupon:issue:sequence:{1}"
-- 쿠폰 ID 가 1 이 아니면 위 명령의 중괄호 안도 함께 바꾼다.
SET @expected_issued_count = NULL;

WITH tc91 AS (
    -- 'issue:' 형식이 아닌 request_id 는 idempotency_key 를 거치지 않은 요청이라
    -- 도착 순서를 알 수 없다(CouponIssueStreamProducer 직접 호출 등). 집계에서 뺀다.
    SELECT CAST(SUBSTRING(request_id, 7) AS UNSIGNED) AS 도착순번,
           sequence_no                                AS 선착순번
      FROM coupon_issue
     WHERE coupon_id = @coupon_id
       AND request_id LIKE 'issue:%'
),
집계 AS (
    SELECT COUNT(*)                 AS 건수,
           MIN(선착순번)            AS 최소순번,
           MAX(선착순번)            AS 최대순번,
           COUNT(DISTINCT 선착순번) AS 고유순번
      FROM tc91
),
역전 AS (
    -- 먼저 도착했는데 순번이 뒤인 쌍의 개수. 건수의 제곱에 비례하므로
    -- 수만 건 이상에서는 오래 걸린다 — TC-91 규모(200건)를 전제로 한다.
    SELECT COUNT(*) AS 역전쌍
      FROM tc91 a
      JOIN tc91 b
        ON a.도착순번 < b.도착순번
       AND a.선착순번 > b.선착순번
)
SELECT '발급 건수' AS 항목, CAST(건수 AS CHAR) AS 값 FROM 집계

UNION ALL
SELECT '비교 쌍', CAST(건수 * (건수 - 1) DIV 2 AS CHAR) FROM 집계

UNION ALL
SELECT '역전 쌍', CAST(역전쌍 AS CHAR) FROM 역전

UNION ALL
SELECT '역전율(%)',
       IFNULL(CAST(ROUND(100 * 역전쌍 / NULLIF(건수 * (건수 - 1) DIV 2, 0), 2) AS CHAR), '측정 불가')
  FROM 집계, 역전

UNION ALL
-- 이것만 PASS/FAIL 판정 대상이다. 역전율과 달리 반드시 지켜져야 한다.
--
-- @expected_issued_count 를 채우면 그 수까지 맞는지 본다(꼬리 유실 검출).
-- 비워두면 DB 에 있는 것끼리만 1..N 이 온전한지 본다.
SELECT '순번 1..N 무결성',
       IF(건수 = 0
          OR (최소순번 = 1
              AND 고유순번 = 건수
              AND 최대순번 = 건수
              AND (@expected_issued_count IS NULL OR 건수 = @expected_issued_count)),
          'PASS', 'FAIL')
  FROM 집계

UNION ALL
-- 위 검사가 어느 범위까지 봤는지 남긴다. 결과만 떼어 보면 구분이 안 되기 때문이다.
SELECT '  └ 검사 범위',
       IF(@expected_issued_count IS NULL,
          'DB 내부만(꼬리 유실 미검출) — verify_issue_result.sql 1번을 함께 볼 것',
          CONCAT('Lua 발급분 ', @expected_issued_count, '건까지 대조'));


-- ---------------------------------------------------------------------
-- 어긋난 건을 눈으로 보고 싶을 때 — 도착 순서대로 나열한다.
-- 선착순번이 오르내리는 구간이 역전이 일어난 지점이다.
-- ---------------------------------------------------------------------
-- SELECT CAST(SUBSTRING(request_id, 7) AS UNSIGNED) AS 도착순번,
--        sequence_no AS 선착순번, created_at AS 저장시각
--   FROM coupon_issue
--  WHERE coupon_id = @coupon_id AND request_id LIKE 'issue:%'
--  ORDER BY 도착순번;
