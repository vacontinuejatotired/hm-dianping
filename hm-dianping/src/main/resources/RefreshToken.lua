-- KEYS[1]: tokenKey = "login:token:" .. userId
-- KEYS[2]: versionKey = "token:version:" .. userId
-- KEYS[3]: refreshKey = "refresh:user:" .. userId

-- ARGV[1]: oldToken (请求携带的 token)
-- ARGV[2]: newToken (新生成的 token)
-- ARGV[3]: expireSeconds (新 token 的过期秒数)
-- ARGV[4]: oldVersion (从 token 中解析的旧版本号)
-- ARGV[5]: clientRefreshToken (请求携带的 refreshToken)

local tokenKey = KEYS[1]
local versionKey = KEYS[2]
local refreshKey = KEYS[3]
local newVersionKey = KEYS[4]
local oldToken = ARGV[1]
local newToken = ARGV[2]
local tokenExpireSeconds = tonumber(ARGV[3])
local oldVersion = tonumber(ARGV[4])
local clientRefreshToken = ARGV[5]
local newVersionExpireSeconds=tonumber(ARGV[6])
local refreshExpireSeconds = tonumber(ARGV[7])
-- 1. 验证 refreshToken
local storedRefresh = redis.call('GET', refreshKey)
if storedRefresh == nil then
    local result = {
        code = 4,
        message = 'refresh token not found'
    }
    return cjson.encode(result)
end

if storedRefresh ~= clientRefreshToken then
    local result = {
        code = 5,
        message = 'refresh token mismatch'
    }
    return cjson.encode(result)
end

-- 2. 验证版本号
local validVersion = redis.call('GET', versionKey)
if validVersion == nil then
    local result = {
        code = 2,
        message = 'version key not found'
    }
    return cjson.encode(result)
end

if tonumber(validVersion) > oldVersion then
    local result = {
        code = 3,
        message = 'token version expired'
    }
    return cjson.encode(result)
end

-- 3. 验证 token 是否存在
local exists = redis.call('EXISTS', tokenKey)
if exists == 0 then
    local result = {
        code = 0,
        message = 'token key not found'
    }
    return cjson.encode(result)
end

-- 4. 验证 token 是否匹配
local storedToken = redis.call('GET', tokenKey)
if storedToken ~= oldToken then
    local result = {
        code = 0,
        message = 'token mismatch'
    }
    return cjson.encode(result)
end

-- 5. 更新 token
redis.call('SET', tokenKey, newToken, 'EX', tokenExpireSeconds)
redis.call('EXPIRE',newVersionKey,newVersionExpireSeconds)
redis.call('EXPIRE', refreshKey, refreshExpireSeconds)
redis.call('EXPIRE',validVersion,refreshExpireSeconds)
-- 6. 返回成功
local result = {
    code = 1,
    message = "refresh deadline token success",
    data = newToken
}
return cjson.encode(result)