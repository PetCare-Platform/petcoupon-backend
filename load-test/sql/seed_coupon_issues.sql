-- =====================================================================
-- 더미 발급 이력 300만 건 생성
-- 대상: coupon_issue 3,000,000 + coupon_issue_history 3,900,000
-- =====================================================================
-- 실행 전 확인
--   - seed_users.sql 로 회원 100만 명이 들어가 있을 것
--       SELECT COUNT(*) FROM app_user WHERE role = 'ROLE_MEMBER';  -- 1000000
--   - 앱을 한 번 띄워 테이블이 생성돼 있을 것 (ddl-auto=update)
--
-- 실행
--   docker compose up -d mysql redis
--   docker cp seed_coupon_issues.sql petcoupon-mysql:/tmp/
--   docker exec petcoupon-mysql mysql -uroot -proot petcoupon -e "source /tmp/seed_coupon_issues.sql"
--   powershell -ExecutionPolicy Bypass -File load-test/scripts/init_seed_coupon_redis.ps1
--
--   PowerShell 은 `< file` 리다이렉션을 지원하지 않으므로 docker cp 로 넣고 source 로 실행한다.
--
-- 왜 쿠폰을 6개로 나누는가
--   uk_issue_coupon_user (coupon_id, user_id) 때문에 쿠폰 1개당 최대 100만 건이다.
--   쿠폰 3개 × 전체 회원으로 하면 300만이 되지만 모든 회원이 똑같이 3장을 갖게 되어
--   "내 쿠폰 목록 조회" 에 편차가 없다. 쿠폰 6개에 회원 범위를 50만씩 겹쳐 잡으면
--   총합은 그대로 300만이면서 회원별 보유량이 1~5장으로 갈린다.
--
-- 이 데이터는 부하 테스트 대상이 아니다
--   이미 발급이 끝난 과거 데이터다. 선착순 발급 테스트는 재고 10,000짜리 별도 쿠폰으로 한다.
--   여기서 만드는 300만 건의 역할은 coupon_issue 테이블을 실제 규모로 채워서,
--   빈 테이블이 아닌 조건에서 발급·조회·정합성 배치를 측정할 수 있게 하는 것이다.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 0. 재실행 대비 정리
--
--    외래키 때문에 이력을 먼저 지운다.
--    SEED- 로 시작하는 쿠폰만 지우므로 부하 테스트용 쿠폰은 건드리지 않는다.
-- ---------------------------------------------------------------------
-- 정합성 검증 상세는 리포트를 참조하므로 리포트보다 먼저 지운다.
DELETE vd
  FROM verification_detail vd
  JOIN reconciliation_report rr ON rr.report_id = vd.report_id
  JOIN coupon c ON c.coupon_id = rr.coupon_id
 WHERE c.name LIKE 'SEED-%';

DELETE rr
  FROM reconciliation_report rr
  JOIN coupon c ON c.coupon_id = rr.coupon_id
 WHERE c.name LIKE 'SEED-%';

DELETE h FROM coupon_issue_history h
  JOIN coupon c ON c.coupon_id = h.coupon_id
 WHERE c.name LIKE 'SEED-%';

DELETE ci FROM coupon_issue ci
  JOIN coupon c ON c.coupon_id = ci.coupon_id
 WHERE c.name LIKE 'SEED-%';

DELETE s FROM coupon_stock s
  JOIN coupon c ON c.coupon_id = s.coupon_id
 WHERE c.name LIKE 'SEED-%';

DELETE FROM coupon WHERE name LIKE 'SEED-%';

-- 이벤트 상태 이력이 존재하면 이벤트보다 먼저 지운다.
DELETE eh
  FROM event_status_history eh
  JOIN event e ON e.event_id = eh.event_id
 WHERE e.name = 'SEED-더미 이벤트';

DELETE FROM event  WHERE name = 'SEED-더미 이벤트';


-- ---------------------------------------------------------------------
-- 1. 더미 이벤트 1개
--
--    created_by 는 회원 시드가 만든 공용 관리자를 쓴다.
--
--    관리자를 먼저 변수에 담고 VALUES 로 넣는다. INSERT ... SELECT 로 하면 관리자가 없을 때
--    0 행이 들어가는데, 그때 LAST_INSERT_ID() 는 갱신되지 않고 세션에 남은 이전 값을 그대로
--    돌려준다. 그 값이 @event_id 가 되어 쿠폰 6개가 무관한 이벤트에 조용히 붙을 수 있다.
--    아래처럼 두면 @admin_id 가 NULL 일 때 created_by 의 NOT NULL 제약에 걸려 여기서 멈춘다.
-- ---------------------------------------------------------------------
SET @admin_id = (SELECT user_id FROM app_user WHERE role = 'ROLE_ADMIN' ORDER BY user_id LIMIT 1);

INSERT INTO event (name, description, open_at, close_at, status, created_by, created_at, updated_at)
VALUES ('SEED-더미 이벤트',
        '부하 테스트용 과거 발급 이력 적재',
        NOW(6) - INTERVAL 90 DAY,
        NOW(6) - INTERVAL 1 DAY,
        'CLOSED',
        @admin_id,
        NOW(6), NOW(6));

SET @event_id = LAST_INSERT_ID();


-- ---------------------------------------------------------------------
-- 2. 더미 쿠폰 6개 + 재고
--
--    쿠폰당 50만 건이 들어갈 예정이므로 총재고를 50만으로 잡는다.
--    발급 기간이 이미 지났으므로 status 는 ENDED 다.
-- ---------------------------------------------------------------------
INSERT INTO coupon
    (event_id, name, discount_type, discount_value, min_order_amount, max_discount_amount,
     issue_start_at, issue_end_at, valid_days, limit_per_member, status, created_at, updated_at)
SELECT @event_id,
       CONCAT('SEED-쿠폰-', n),
       'FIXED_AMOUNT', 1000 * n, 10000, NULL,
       NOW(6) - INTERVAL 90 DAY,
       NOW(6) - INTERVAL 1 DAY,
       365, 1, 'ENDED', NOW(6), NOW(6)
  FROM (SELECT 1 AS n UNION ALL SELECT 2 UNION ALL SELECT 3
        UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6) t;

INSERT INTO coupon_stock (coupon_id, total_quantity, issued_quantity, remaining_quantity, updated_at)
SELECT coupon_id, 500000, 0, 500000, NOW(6)
  FROM coupon WHERE name LIKE 'SEED-쿠폰-%';


-- ---------------------------------------------------------------------
-- 3. 회원에 1~1,000,000 순번을 매긴 임시 테이블
--
--    user_id 는 연속이 아닐 수 있다(관리자가 섞여 있음). 범위를 자르려면
--    회원만 골라 다시 번호를 매겨야 한다.
--    300만 건 INSERT 에서 6번 참조하므로 CTE 가 아니라 실제 테이블로 만든다.
--    (MySQL 은 CTE 를 매번 다시 계산하므로 100만 행 정렬이 6번 반복된다)
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS seed_member_seq;

CREATE TABLE seed_member_seq (
    n       BIGINT NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    KEY idx_seed_member_user (user_id)
) ENGINE = InnoDB;

INSERT INTO seed_member_seq (n, user_id)
SELECT ROW_NUMBER() OVER (ORDER BY user_id), user_id
  FROM app_user
 WHERE role = 'ROLE_MEMBER';

-- 회원이 부족하면 뒤쪽 범위의 INSERT가 조용히 적은 건수로 끝나므로 여기서 즉시 중단한다.
DROP PROCEDURE IF EXISTS assert_seed_member_count;
DELIMITER //
CREATE PROCEDURE assert_seed_member_count()
BEGIN
    DECLARE v_member_count BIGINT;
    SELECT COUNT(*) INTO v_member_count FROM seed_member_seq;

    IF v_member_count < 1000000 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SEED 중단: ROLE_MEMBER 회원이 최소 1,000,000명이어야 합니다.';
    END IF;
END//
DELIMITER ;

CALL assert_seed_member_count();
DROP PROCEDURE assert_seed_member_count;


-- ---------------------------------------------------------------------
-- 4. 발급 이력 300만 건
--
--    쿠폰 6개에 회원 범위를 50만씩 겹쳐 배정한다.
--      쿠폰1  n      1 ~ 500,000
--      쿠폰2  n 100,001 ~ 600,000
--      쿠폰3  n 200,001 ~ 700,000
--      쿠폰4  n 300,001 ~ 800,000
--      쿠폰5  n 400,001 ~ 900,000
--      쿠폰6  n 500,001 ~ 1,000,000
--    회원별 보유량은 1~5장으로 갈리고 총합은 정확히 300만이다.
--
--    유니크 값은 전부 순번에서 파생시킨다. 랜덤을 쓰면 충돌 검사가 필요하지만
--    순번 기반이면 중복이 구조적으로 불가능하다.
--      sequence_no   쿠폰 안에서 1부터 (uk_issue_sequence)
--      coupon_code   'SEED' + 쿠폰id의 16자리 HEX + 순번 (전역 유니크, 30자)
--      request_id    'seed-' + 쿠폰id + '-' + 순번 (전역 유니크)
--
--    status 는 (회원 순번 + 쿠폰 ID) 끝자리로 나눈다 — ISSUED 70% / USED 20% / EXPIRED 10%.
--
--    회원 순번만 쓰면 안 된다. m.n 은 쿠폰이 달라도 같은 값이라, 한 회원이 5장을 갖고 있으면
--    5장 모두 같은 상태가 된다. "내 쿠폰 목록에 ISSUED·USED·EXPIRED 가 섞여 있는" 상황을
--    만들려면 쿠폰마다 달라지는 값을 섞어야 한다. @c 를 더해 그 상관관계를 끊는다.
--    ISSUED 의 expires_at 은 반드시 미래로 둔다. 과거로 두면 만료 배치가
--    이 더미를 EXPIRED 로 바꿔버려서 기준선이 흔들린다.
--
--    쿠폰 하나씩 6번 나눠 실행한다. 300만 건을 한 트랜잭션에 넣으면 느리고,
--    중간에 끊기면 처음부터다. 공통 프로시저에 쿠폰 번호와 회원 범위만 전달해
--    중복 코드를 없애면서도 쿠폰별 INSERT 단위는 유지한다.
-- ---------------------------------------------------------------------

DROP PROCEDURE IF EXISTS insert_seed_coupon_issues;
DELIMITER //
CREATE PROCEDURE insert_seed_coupon_issues(
    IN p_coupon_no INT,
    IN p_from BIGINT,
    IN p_to BIGINT
)
BEGIN
    DECLARE v_coupon_id BIGINT;

    SELECT coupon_id INTO v_coupon_id
      FROM coupon
     WHERE name = CONCAT('SEED-쿠폰-', p_coupon_no);

    IF v_coupon_id IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SEED 중단: 대상 쿠폰을 찾을 수 없습니다.';
    END IF;

    INSERT INTO coupon_issue
        (coupon_id, user_id, sequence_no, coupon_code, request_id,
         status, used_at, expires_at, created_at, updated_at)
    SELECT v_coupon_id,
           m.user_id,
           m.n - p_from + 1,
           -- BIGINT 쿠폰 ID를 16자리 16진수로 표현하면 잘림 없이 coupon_code 32자 제한 안에 들어간다.
           CONCAT('SEED', LPAD(HEX(v_coupon_id), 16, '0'), LPAD(m.n, 10, '0')),
           CONCAT('seed-', v_coupon_id, '-', m.n),
           CASE WHEN (m.n + v_coupon_id) % 10 = 9 THEN 'EXPIRED'
                WHEN (m.n + v_coupon_id) % 10 >= 7 THEN 'USED'
                ELSE 'ISSUED' END,
           CASE WHEN (m.n + v_coupon_id) % 10 BETWEEN 7 AND 8
                THEN NOW(6) - INTERVAL (m.n % 30) DAY END,
           CASE WHEN (m.n + v_coupon_id) % 10 = 9 THEN NOW(6) - INTERVAL 1 DAY
                ELSE NOW(6) + INTERVAL 365 DAY END,
           NOW(6) - INTERVAL 30 DAY,
           NOW(6) - INTERVAL 30 DAY
      FROM seed_member_seq m
     WHERE m.n BETWEEN p_from AND p_to;
END//
DELIMITER ;

CALL insert_seed_coupon_issues(1,      1,  500000);
CALL insert_seed_coupon_issues(2, 100001,  600000);
CALL insert_seed_coupon_issues(3, 200001,  700000);
CALL insert_seed_coupon_issues(4, 300001,  800000);
CALL insert_seed_coupon_issues(5, 400001,  900000);
CALL insert_seed_coupon_issues(6, 500001, 1000000);

DROP PROCEDURE insert_seed_coupon_issues;


-- ---------------------------------------------------------------------
-- 5. 상태 이력
--
--    정합성 검증 배치는 "현재 status 와 최종 이력의 to_status 가 같은가" 를 본다.
--    이력이 아예 없는 발급 건도 불일치로 잡으므로, 여기를 건너뛰면
--    300만 건 전부가 HISTORY_MISMATCH 로 리포트된다.
--
--    coupon_issue 를 읽어서 파생시키므로 둘이 어긋날 수 없다.
--      전체        NONE   -> ISSUED
--      USED 건     ISSUED -> USED
--      EXPIRED 건  ISSUED -> EXPIRED
--    history_id 가 AUTO_INCREMENT 라 나중에 넣은 행이 뒤에 오고,
--    배치는 MAX(history_id) 를 최종 이력으로 보므로 순서가 맞는다.
-- ---------------------------------------------------------------------
INSERT INTO coupon_issue_history
    (coupon_issue_id, coupon_id, user_id, from_status, to_status, actor_type, reason, created_at)
SELECT ci.coupon_issue_id, ci.coupon_id, ci.user_id, 'NONE', 'ISSUED', 'SYSTEM', '더미 적재',
       ci.created_at
  FROM coupon_issue ci
  JOIN coupon c ON c.coupon_id = ci.coupon_id
 WHERE c.name LIKE 'SEED-%';

INSERT INTO coupon_issue_history
    (coupon_issue_id, coupon_id, user_id, from_status, to_status, actor_type, actor_id, reason, created_at)
SELECT ci.coupon_issue_id, ci.coupon_id, ci.user_id, 'ISSUED', 'USED', 'USER', ci.user_id, '더미 적재',
       ci.used_at
  FROM coupon_issue ci
  JOIN coupon c ON c.coupon_id = ci.coupon_id
 WHERE c.name LIKE 'SEED-%' AND ci.status = 'USED';

INSERT INTO coupon_issue_history
    (coupon_issue_id, coupon_id, user_id, from_status, to_status, actor_type, reason, created_at)
SELECT ci.coupon_issue_id, ci.coupon_id, ci.user_id, 'ISSUED', 'EXPIRED', 'BATCH', '더미 적재',
       ci.expires_at
  FROM coupon_issue ci
  JOIN coupon c ON c.coupon_id = ci.coupon_id
 WHERE c.name LIKE 'SEED-%' AND ci.status = 'EXPIRED';


-- ---------------------------------------------------------------------
-- 6. 재고를 실제 발급 수와 맞춘다
--
--    안 맞추면 정합성 배치의 재고 검증이 처음부터 불일치로 나온다.
-- ---------------------------------------------------------------------
--    서브쿼리 안에서 대상 쿠폰을 먼저 좁힌다. 조건 없이 GROUP BY 하면 coupon_issue 전체를
--    집계한 뒤에야 바깥에서 SEED-% 로 걸러내는데, 이 테이블에는 선착순 테스트 데이터도 함께
--    쌓이므로 그럴수록 불필요하게 전체를 훑게 된다.
UPDATE coupon_stock s
  JOIN (SELECT ci.coupon_id, COUNT(*) AS cnt
          FROM coupon_issue ci
         WHERE ci.coupon_id IN (SELECT coupon_id FROM coupon WHERE name LIKE 'SEED-%')
         GROUP BY ci.coupon_id) x
    ON x.coupon_id = s.coupon_id
  JOIN coupon c ON c.coupon_id = s.coupon_id
   SET s.issued_quantity    = x.cnt,
       s.remaining_quantity = s.total_quantity - x.cnt,
       s.updated_at         = NOW(6)
 WHERE c.name LIKE 'SEED-%';


-- ---------------------------------------------------------------------
-- 6-1. Redis 재고 초기화
--
--    #111 정합성 배치는 DB coupon_stock.remaining_quantity와 Redis의
--    coupon:issue:stock:{couponId} 값을 비교한다. SQL에서는 Redis에 직접 접근할 수 없으므로
--    이 파일 실행이 끝난 뒤 아래 보조 스크립트를 반드시 실행한다.
--
--    powershell -ExecutionPolicy Bypass -File load-test/scripts/init_seed_coupon_redis.ps1
--
--    SEED 쿠폰은 500,000건이 모두 발급돼 remaining_quantity=0이며, 스크립트는 값을
--    하드코딩하지 않고 DB에서 직접 읽어 Redis에 복사한다. Redis sequence 키는 #111 배치의
--    검증 대상이 아니므로 만들지 않는다.
-- ---------------------------------------------------------------------


-- ---------------------------------------------------------------------
-- 7. 임시 테이블 정리
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS seed_member_seq;


-- =====================================================================
-- 검증 — 아래를 따로 실행해 확인한다
-- =====================================================================
-- 총 건수: 3000000 이어야 한다
--   SELECT COUNT(*) FROM coupon_issue ci JOIN coupon c ON c.coupon_id = ci.coupon_id
--    WHERE c.name LIKE 'SEED-%';
--
-- 이력 건수: 발급 건수 + USED 수 + EXPIRED 수
--   SELECT COUNT(*) FROM coupon_issue_history h JOIN coupon c ON c.coupon_id = h.coupon_id
--    WHERE c.name LIKE 'SEED-%';
--
-- 쿠폰별 500000 씩인가
--   SELECT coupon_id, COUNT(*) FROM coupon_issue GROUP BY coupon_id;
--
-- 회원별 보유량이 1~5 로 갈리는가
--   SELECT cnt, COUNT(*) FROM (
--     SELECT user_id, COUNT(*) AS cnt FROM coupon_issue GROUP BY user_id) t
--    GROUP BY cnt ORDER BY cnt;
--
-- 1인 1매 위반 0건
--   SELECT COUNT(*) FROM (
--     SELECT coupon_id, user_id FROM coupon_issue
--      GROUP BY coupon_id, user_id HAVING COUNT(*) > 1) t;
--
-- 순번이 쿠폰별 1~500000 연속인가
--   SELECT coupon_id, COUNT(*), COUNT(DISTINCT sequence_no), MIN(sequence_no), MAX(sequence_no)
--     FROM coupon_issue GROUP BY coupon_id;
--
-- 상태와 최종 이력이 어긋난 건 0건 (정합성 배치와 같은 기준)
--   SELECT COUNT(*) FROM coupon_issue ci
--     JOIN coupon c ON c.coupon_id = ci.coupon_id
--     LEFT JOIN coupon_issue_history h
--       ON h.history_id = (SELECT MAX(h2.history_id) FROM coupon_issue_history h2
--                           WHERE h2.coupon_issue_id = ci.coupon_issue_id)
--    WHERE c.name LIKE 'SEED-%'
--      AND (h.to_status IS NULL OR ci.status <> h.to_status);
--
-- 재고가 발급 수와 맞는가
--   SELECT s.coupon_id, s.total_quantity, s.issued_quantity, s.remaining_quantity
--     FROM coupon_stock s JOIN coupon c ON c.coupon_id = s.coupon_id
--    WHERE c.name LIKE 'SEED-%';
--
-- =====================================================================
-- 정리 (전부 지우고 싶을 때)
--   이 스크립트 맨 위 "0. 재실행 대비 정리" 블록만 실행하면 된다.
-- =====================================================================
