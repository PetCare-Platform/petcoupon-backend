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
-- 도착 순서를 어디서 얻는가
-- ---------------------------------------------------------------------
--   시나리오 문서는 "서버 도착 로그"라고 적고 있지만, 컨트롤러에는 진입 로그가 없다.
--   대신 request_id 가 그 역할을 한다.
--
--     request_id = 'issue:{idempotency_id}'
--
--   idempotency_id 는 컨트롤러가 begin() 으로 idempotency_key 행을 만들 때 받는
--   AUTO_INCREMENT 다. 즉 "서버가 이 요청을 받아들인 순서"와 같다.
--   sequence_no 는 그보다 뒤인 Lua 판정 시점에 매겨진다.
--   둘의 순서가 어긋난 정도가 역전율이다.
--
-- 역전율은 얼마여야 하는가
-- ---------------------------------------------------------------------
--   기준값이 없다. 동시 요청은 도착 순서 자체가 확정적이지 않아 완전 일치를
--   기대하지 않는다(시나리오 문서 G 구간 참고). 이 수치는 판정용이 아니라 기록용이다.
--
--   반드시 지켜져야 하는 건 아래 "순번 1..N 무결성" 하나다.
--   Lua 가 순번을 내줬는데 DB 에 반영되지 않은 건이 있으면 여기서 FAIL 이 난다.
-- =====================================================================

SET @coupon_id = 1;

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
SELECT '순번 1..N 무결성',
       IF(건수 = 0
          OR (최소순번 = 1 AND 최대순번 = 건수 AND 고유순번 = 건수), 'PASS', 'FAIL')
  FROM 집계;


-- ---------------------------------------------------------------------
-- 어긋난 건을 눈으로 보고 싶을 때 — 도착 순서대로 나열한다.
-- 선착순번이 오르내리는 구간이 역전이 일어난 지점이다.
-- ---------------------------------------------------------------------
-- SELECT CAST(SUBSTRING(request_id, 7) AS UNSIGNED) AS 도착순번,
--        sequence_no AS 선착순번, created_at AS 저장시각
--   FROM coupon_issue
--  WHERE coupon_id = @coupon_id AND request_id LIKE 'issue:%'
--  ORDER BY 도착순번;
