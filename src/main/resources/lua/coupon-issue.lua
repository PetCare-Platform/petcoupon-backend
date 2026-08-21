-- KEYS[1]: 쿠폰 재고 Key
-- KEYS[2]: 쿠폰별 신청 사용자 요청 Hash Key
--
-- ARGV[1]: userId
-- ARGV[2]: requestId

local stockKey = KEYS[1]
local applicantKey = KEYS[2]

local userId = ARGV[1]
local requestId = ARGV[2]

local existingRequestId = redis.call(
    'HGET',
    applicantKey,
    userId
)

if existingRequestId then
    if existingRequestId == requestId then
        return 4
    end

    return 2
end

local stock = tonumber(redis.call('GET', stockKey) or '0')

if stock <= 0 then
    return 3
end

redis.call('DECR', stockKey)
redis.call('HSET', applicantKey, userId, requestId)

return 1