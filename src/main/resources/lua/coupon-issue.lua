-- KEYS[1]: 쿠폰 재고 Key
-- KEYS[2]: 쿠폰별 사용자별 최초 요청 ID Hash Key
-- KEYS[3]: 쿠폰별 발급 순번 Key
-- KEYS[4]: 쿠폰별 요청 ID별 발급 순번 Hash Key

-- ARGV[1]: userId
-- ARGV[2]: requestId

local stockKey = KEYS[1]
local applicantKey = KEYS[2]
local sequenceKey = KEYS[3]
local requestSequenceKey = KEYS[4]

local userId = ARGV[1]
local requestId = ARGV[2]

-- 동일 사용자 신청 여부 확인
local existingRequestId = redis.call(
    'HGET',
    applicantKey,
    userId
)

if existingRequestId then
	-- 동일 요청 재처리 : 최초 발급 순번을 반환
    if existingRequestId == requestId then
		local existingSequence = redis.call(
			'HGET',  
			 requestSequenceKey, 
			 requestId
		 )
			
		 -- 신청 이력은 있지만 순번 이력이 없는 Redis 데이터 불일치 상태
		 if not existingSequence then  
			return { 6, 0 }
		end
		
		return { 4, tonumber(existingSequence) }
	end
		
	-- 같은 사용자의 다른 요청 : 이미 신청함
    return { 2, 0 }
end

-- 재고 Key가 없으면 Redis 초기화, 복구 문제로 간주
local stockValue = redis.call('GET', stockKey)

if not stockValue then
    return { 5, 0 }
end

local stock = tonumber(stockValue)

-- 품절
if stock <= 0 then
    return { 3, 0 }
end

-- 재고가 있는 첫 요청에만 선착순 순번 발급
local sequenceNo = redis.call('INCR', sequenceKey)

redis.call('DECR', stockKey)
redis.call('HSET', applicantKey, userId, requestId)
redis.call('HSET', requestSequenceKey, requestId, sequenceNo)

-- { Lua 결과 코드, 선착순 발급 순번 }
return { 1, sequenceNo }