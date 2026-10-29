-- Token bucket, evaluated atomically inside Redis so concurrent requests from many API
-- replicas can't both spend the last token.
-- KEYS[1]  bucket key
-- ARGV[1]  capacity (max tokens)
-- ARGV[2]  refill rate, tokens per second
-- ARGV[3]  cost of this request
-- Returns {allowed (1/0), milliseconds until enough tokens for this cost}
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local cost = tonumber(ARGV[3])

-- Redis' clock, not the caller's: replicas with skewed clocks would otherwise mint tokens.
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil then
  tokens = capacity
  ts = now
end

-- Refill lazily for the time since the last request instead of running a timer per bucket.
tokens = math.min(capacity, tokens + (now - ts) / 1000 * rate)

local allowed = 0
local wait_ms = 0
if tokens >= cost then
  tokens = tokens - cost
  allowed = 1
else
  wait_ms = math.ceil((cost - tokens) / rate * 1000)
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', now)
-- An idle bucket is full again after capacity/rate seconds, so it can be forgotten then.
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / rate * 1000) + 1000)
return {allowed, wait_ms}
