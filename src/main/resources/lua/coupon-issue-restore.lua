-- KEYS[1]: 쿠폰 잔여 재고 Key
-- KEYS[2]: 쿠폰별 사용자 신청 정보 Hash Key
-- KEYS[3]: 쿠폰별 요청 순번 정보 Hash Key

-- ARGV[1]: userId
-- ARGV[2]: requestId
-- ARGV[3]: sequenceNo

local stockKey = KEYS[1]
local applicantsKey = KEYS[2]
local requestSequenceKey = KEYS[3]

local userId = ARGV[1]
local requestId = ARGV[2]
local expectedSequenceNo = ARGV[3]

-- 재고 키가 없으면 복구할 수 없다.
local stockValue = redis.call('GET', stockKey)

if not stockValue then
    return { 4, 0 }
end

local stock = tonumber(stockValue)

-- 숫자가 아니거나 음수인 재고는 정합성 오류로 처리한다.
if not stock or stock < 0 then
    return { 5, 0 }
end

local currentRequestId = redis.call(
    'HGET',
    applicantsKey,
    userId
)

local storedSequenceNo = redis.call(
    'HGET',
    requestSequenceKey,
    requestId
)

-- 신청자 기록과 요청 순번 기록이 모두 없으면 이미 복구된 상태로 본다.
-- 같은 복구 요청이 다시 들어와도 재고를 추가 증가시키지 않는다.
if not currentRequestId and not storedSequenceNo then
    return { 2, stock }
end

-- 순번 기록은 있지만 신청 기록이 없으면 Redis 데이터 정합성 오류다.
-- 이미 정상 복구된 상태라면 두 기록이 모두 삭제되어 있어야 한다.
if not currentRequestId and storedSequenceNo then
    return { 5, stock }
end

-- 현재 사용자의 신청이 다른 요청이면 건드리지 않는다.
-- 복구 후 사용자가 새로 신청한 경우, 이전 요청의 재복구가 새 신청을 제거하면 안 된다.
if currentRequestId ~= requestId then
    return { 3, stock }
end

-- 신청 기록은 현재 요청과 일치하지만 순번 기록이 없으면
-- Redis 데이터 정합성 오류다.
if not storedSequenceNo then
    return { 5, stock }
end

-- Redis에 저장된 순번과 전달받은 복구 대상 순번이 다르면
-- 다른 발급 건일 수 있으므로 복구하지 않는다.
if storedSequenceNo ~= expectedSequenceNo then
    return { 5, stock }
end

-- 재고 및 신청 정보를 원자적으로 복구한다.
redis.call('HDEL', applicantsKey, userId)
redis.call('HDEL', requestSequenceKey, requestId)

local restoredStock = redis.call('INCR', stockKey)

-- 전역 sequenceKey는 선착순 순번이므로 감소시키지 않는다.
return { 1, restoredStock }