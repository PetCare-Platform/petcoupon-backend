-- KEYS[1]: 쿠폰 재고 Key
-- KEYS[2]: 쿠폰별 신청 사용자 Set Key
-- KEYS[3]: Redis Stream Key
--
-- ARGV[1]: couponId
-- ARGV[2]: userId
-- ARGV[3]: requestId

local stockKey = KEYS[1]
local applicantKey = KEYS[2]
local streamKey = KEYS[3]

local couponId = ARGV[1]
local userId = ARGV[2]
local requestId = ARGV[3]

-- 이미 신청한 사용자인 경우
if redis.call('SISMEMBER', applicantKey, userId) == 1 then
    return 2
end

-- 재고 확인
local stock = tonumber(redis.call('GET', stockKey) or '0')

if stock <= 0 then
    return 3
end

-- 재고 차감 및 신청자 등록
redis.call('DECR', stockKey)
redis.call('SADD', applicantKey, userId)

-- Worker가 처리할 발급 요청 발행
redis.call(
    'XADD',
    streamKey,
    '*',
    'couponId', couponId,
    'userId', userId,
    'requestId', requestId
)

return 1