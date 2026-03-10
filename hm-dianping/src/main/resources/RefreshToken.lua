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

local oldToken = ARGV[1]
local newToken = ARGV[2]
local tokenExpireSeconds = tonumber(ARGV[3])
local oldVersion = tonumber(ARGV[4])
local clientRefreshToken = ARGV[5]

-- 1. 验证 refreshToken
local storedRefresh = redis.call('GET', refreshKey)
if storedRefresh == nil then
    return 421  -- REFRESH_TOKEN_NOT_FOUND
end

if storedRefresh ~= clientRefreshToken then
    return 422  -- REFRESH_TOKEN_MISMATCH
end

-- 2. 验证版本号
local validVersion = redis.call('GET', versionKey)
if validVersion == nil then
    return 411  -- VERSION_KEY_NOT_FOUND
end

if tonumber(validVersion) > oldVersion then
    return 412  -- TOKEN_VERSION_EXPIRED
end

-- 3. 验证 token 是否存在
local exists = redis.call('EXISTS', tokenKey)
if exists == 0 then
    return 401  -- TOKEN_KEY_NOT_FOUND
end

-- 4. 验证 token 是否匹配
local storedToken = redis.call('GET', tokenKey)
if storedToken ~= oldToken then
    return 402  -- TOKEN_MISMATCH
end

-- 5. 更新 token
redis.call('SET', tokenKey, newToken, 'EX', tokenExpireSeconds)

-- 6. 返回成功
return 200  -- SUCCESS