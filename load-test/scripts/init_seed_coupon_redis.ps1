param(
    [string]$MySqlContainer = "petcoupon-mysql",
    [string]$RedisContainer = "petcoupon-redis",
    [string]$Database = "petcoupon",
    [string]$MySqlUser = "root",
    [string]$MySqlPassword = "root"
)

$ErrorActionPreference = "Stop"

$query = @"
SELECT c.coupon_id, s.remaining_quantity
  FROM coupon c
  JOIN coupon_stock s ON s.coupon_id = c.coupon_id
 WHERE c.name LIKE 'SEED-쿠폰-%'
 ORDER BY c.coupon_id
"@

$mysqlArgs = @(
    "exec",
    $MySqlContainer,
    "mysql",
    "--default-character-set=utf8mb4",
    "--batch",
    "--skip-column-names",
    "-u$MySqlUser",
    "-p$MySqlPassword",
    $Database,
    "-e",
    $query
)

$rows = @(& docker @mysqlArgs 2>$null)

if ($LASTEXITCODE -ne 0) {
    throw "SEED 쿠폰 재고 조회에 실패했습니다. MySQL 컨테이너와 접속 정보를 확인하세요."
}

if ($rows.Count -ne 6) {
    throw "SEED 쿠폰이 6개여야 합니다. 조회 결과: $($rows.Count)개"
}

foreach ($row in $rows) {
    $columns = $row -split "`t"
    if ($columns.Count -ne 2) {
        throw "예상하지 못한 MySQL 조회 결과입니다: $row"
    }

    $couponId = $columns[0]
    $remainingQuantity = $columns[1]
    $redisKey = "coupon:issue:stock:{$couponId}"

    $result = & docker exec $RedisContainer redis-cli SET $redisKey $remainingQuantity
    if ($LASTEXITCODE -ne 0 -or $result -ne "OK") {
        throw "Redis 재고 초기화에 실패했습니다. key=$redisKey result=$result"
    }

    Write-Host "Redis 재고 초기화 완료: $redisKey=$remainingQuantity"
}
