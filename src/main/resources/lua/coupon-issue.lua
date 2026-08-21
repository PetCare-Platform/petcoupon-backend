-- KEYS[1]: 쿠폰 재고 Key
-- KEYS[2]: 쿠폰별 신청 사용자 Set Key
--
-- ARGV[1]: userId

local stockKey = KEYS[1]
local applicantKey = KEYS[2]
local userId = ARGV[1]

if redis.call('SISMEMBER', applicantKey, userId) == 1 then
    return 2
end

local stock = tonumber(redis.call('GET', stockKey) or '0')

if stock <= 0 then
    return 3
end

redis.call('DECR', stockKey)
redis.call('SADD', applicantKey, userId)

return 1