-- Token-bucket rate limiter (atomic).
--
-- KEYS[1]: bucket key
-- ARGV[1]: now (epoch millis)
-- ARGV[2]: capacity (integer tokens)
-- ARGV[3]: refill-per-second (float tokens/sec)
-- ARGV[4]: cost (integer; usually 1)
--
-- Returns: { allowed (0|1), remaining (integer tokens), retryAfterMs, resetAtMs }

local key      = KEYS[1]
local now      = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local refill   = tonumber(ARGV[3])
local cost     = tonumber(ARGV[4])

local data    = redis.call('HMGET', key, 'tokens', 'updated')
local tokens  = tonumber(data[1])
local updated = tonumber(data[2])

if tokens == nil then
    tokens = capacity
    updated = now
end

local elapsedSec = math.max(0, now - updated) / 1000.0
tokens = math.min(capacity, tokens + elapsedSec * refill)

local allowed = 0
if tokens >= cost then
    tokens = tokens - cost
    allowed = 1
end

redis.call('HMSET', key, 'tokens', tostring(tokens), 'updated', tostring(now))

-- TTL: a little longer than full-refill window so the key auto-expires when idle.
local ttlMs = 1000
if refill > 0 then
    ttlMs = math.ceil((capacity / refill) * 1000) + 1000
end
redis.call('PEXPIRE', key, ttlMs)

local retryAfterMs = 0
local resetAtMs    = now
if refill > 0 then
    if allowed == 0 then
        retryAfterMs = math.ceil(((cost - tokens) / refill) * 1000)
    end
    resetAtMs = now + math.ceil(((capacity - tokens) / refill) * 1000)
end

return { allowed, math.floor(tokens), retryAfterMs, resetAtMs }
