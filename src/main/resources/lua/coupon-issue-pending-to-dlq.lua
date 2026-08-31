-- KEYS[1]: 원본 Redis Stream Key
-- KEYS[2]: DLQ Redis Stream Key
--
-- ARGV[1]: Consumer Group
-- ARGV[2]: 원본 Message ID
-- ARGV[3] 이후: DLQ에 저장할 field/value 쌍

local sourceStreamKey = KEYS[1]
local dlqStreamKey = KEYS[2]

local group = ARGV[1]
local originalMessageId = ARGV[2]

-- DLQ 저장이 실패하면 여기서 Script가 중단되므로 원본은 ACK되지 않는다.
local dlqMessageId = redis.call(
    'XADD',
    dlqStreamKey,
    '*',
    unpack(ARGV, 3)
)

local acknowledgedCount = redis.call(
    'XACK',
    sourceStreamKey,
    group,
    originalMessageId
)

-- 원본 Consumer가 먼저 ACK했다면 방금 생성한 잘못된 DLQ 기록을 제거한다.
if acknowledgedCount == 0 then
    redis.call('XDEL', dlqStreamKey, dlqMessageId)
    return 'ALREADY_ACKNOWLEDGED'
end

return dlqMessageId