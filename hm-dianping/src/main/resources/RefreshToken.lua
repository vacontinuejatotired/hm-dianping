
-- KEYS[1]: tokenKey = "login:token:" .. userId
-- ARGV[1]: oldToken (请求携带的 token)
-- ARGV[2]: newToken (新生成的 token)
-- ARGV[3]: expireSeconds (新 token 的过期秒数，例如 30*60 = 1800)

local tokenKey = KEYS[1]
local oldToken = ARGV[1]
local newToken = ARGV[2]
local expireSeconds = tonumber(ARGV[3])
local exists = redis.call('EXISTS', tokenKey)
if exists == 0 then
    return '{"code":0,"message":"token key not found"}'
end
local storedToken = redis.call('GET', tokenKey)
if storedToken ~= oldToken then
    return '{"code":0,"message":"token mismatch"}'
end
redis.call('DEL', tokenKey)
redis.call('SET', tokenKey, newToken, 'EX', expireSeconds)
return '{"code":1,"message":"success","data":"' .. newToken .. '"}'