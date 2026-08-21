-- =====================================================================
-- 더미 회원 100만 명 생성
-- 대상: app_user (role = ROLE_MEMBER) + 관리자 1명
-- =====================================================================
-- 실행 전 확인
--   - app_user 테이블이 생성돼 있을 것 (ddl-auto=update 이므로 앱을 한 번 띄우면 생성됨)
--   - 기존 더미가 있다면 맨 아래 "정리" 참고
--   - PC 에 MySQL 이 따로 설치돼 있으면 3306 포트가 겹쳐 컨테이너가 뜨지 않는다.
--     관리자 PowerShell 에서 `net stop MySQL80` 으로 로컬 서비스를 멈추고 실행한다.
--
-- 실행 (docker-compose.yml 이 있는 폴더에서 MySQL 컨테이너를 먼저 띄운다)
--   docker compose up -d mysql
--   docker cp seed_users.sql petcoupon-mysql:/tmp/
--   docker exec petcoupon-mysql mysql -uroot -proot petcoupon -e "source /tmp/seed_users.sql"
--
--   PowerShell 은 `< file` 리다이렉션을 지원하지 않으므로 docker cp 로 넣고 source 로 실행한다.
--
-- 검증 결과 (로컬 MySQL 8.0, 2026-08-21)
--   총 1,000,001건 (회원 1,000,000 + SHARED_ADMIN 1)
--   uuid / email / phone 모두 중복 0건
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 회원 100만 명 INSERT
--
--    digits(0~9) 를 6번 CROSS JOIN 해서 10^6 = 정확히 1,000,000 행을 만든다.
--    임시 테이블 대신 CTE 를 쓰는 이유: MySQL 은 임시 테이블을 한 쿼리에서
--    두 번 이상 참조할 수 없다 (ERROR 1137 Can't reopen table).
--
--    email / phone 은 NULL 이면 안 된다.
--    ck_user_member_contact 가 ROLE_MEMBER 는 둘 다 필수라고 막고 있다.
--
--    100만 건이 한 트랜잭션이라 로컬 사양에 따라 수십 초~수 분 걸린다.
--    느리거나 타임아웃이 나면 아래 WHERE 주석을 풀어 10만 건씩 나눠 실행한다.
--      WHERE n BETWEEN 1 AND 100000    → 이후 100001~200000 ... 반복
-- ---------------------------------------------------------------------
INSERT INTO app_user (uuid, name, email, phone, role, status)
WITH digits AS (
    SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
),
seq AS (
    SELECT
        d1.d
        + d2.d * 10
        + d3.d * 100
        + d4.d * 1000
        + d5.d * 10000
        + d6.d * 100000
        + 1 AS n
    FROM digits d1
    CROSS JOIN digits d2
    CROSS JOIN digits d3
    CROSS JOIN digits d4
    CROSS JOIN digits d5
    CROSS JOIN digits d6
)
SELECT
    UUID(),
    CONCAT('테스트회원', n),
    CONCAT('user', n, '@test.com'),
    -- 010-XXXX-XXXX. n 이 다르면 번호도 다르다.
    CONCAT('010-', LPAD(FLOOR(n / 10000), 4, '0'), '-', LPAD(n % 10000, 4, '0')),
    'ROLE_MEMBER',
    'ACTIVE'
FROM seq
-- WHERE n BETWEEN 1 AND 100000   -- 나눠서 실행할 때 주석 해제
ORDER BY n;

-- ---------------------------------------------------------------------
-- 2. 관리자 계정 (SHARED_ADMIN)
--    event.created_by 가 참조한다. 없으면 이벤트 생성 API 가
--    INTERNAL_SERVER_ERROR 로 실패한다 (EventServiceImpl.findActiveAdmin).
--    이미 있으면 넣지 않는다. MySQL 은 FROM 없이 WHERE 를 못 쓰므로 FROM DUAL.
-- ---------------------------------------------------------------------
INSERT INTO app_user (uuid, name, email, phone, role, status)
SELECT UUID(), 'SHARED_ADMIN', 'admin@test.com', '010-0000-0000', 'ROLE_ADMIN', 'ACTIVE'
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM app_user WHERE role = 'ROLE_ADMIN'
);

-- ---------------------------------------------------------------------
-- 3. 결과 확인
-- ---------------------------------------------------------------------
SELECT
    role,
    status,
    COUNT(*)     AS cnt,
    MIN(user_id) AS min_user_id,
    MAX(user_id) AS max_user_id
FROM app_user
GROUP BY role, status;

-- 기대: uuid_total 과 uuid_distinct 가 같아야 한다
SELECT COUNT(*) AS uuid_total, COUNT(DISTINCT uuid) AS uuid_distinct FROM app_user;

-- =====================================================================
-- 정리 (재실행 전)
--   coupon_issue 등이 user_id 를 FK 로 참조하므로 자식 테이블부터 비운다.
--   AUTO_INCREMENT 초기화는 테이블이 완전히 비었을 때만 의미가 있다.
-- =====================================================================
-- DELETE FROM app_user WHERE role = 'ROLE_MEMBER';
-- ALTER TABLE app_user AUTO_INCREMENT = 1;
