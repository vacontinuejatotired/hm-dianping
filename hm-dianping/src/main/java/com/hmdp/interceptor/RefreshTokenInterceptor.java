package com.hmdp.interceptor;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.hmdp.Enum.TokenRefreshCode;
import com.hmdp.entity.TokenVersionCache;
import com.hmdp.entity.UserinfoCache;
import com.hmdp.service.IUserService;
import com.hmdp.utils.*;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {

    @Resource
    private JwtUtil jwtUtil;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;
    @Resource(name = "refreshDeadTokenScript")
    private DefaultRedisScript<Long> refreshDeadTokenScript;
    @Resource(name = "refreshDeadlineTokenScript")
    private DefaultRedisScript<Long> refreshDeadlineTokenScript;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private BatchLoadCache batchLoadCache;
    @Resource(name = "userinfoCache")
    private LoadingCache<String, UserinfoCache> userinfoCaffeine;
    @Resource(name = "tokenValidVersionCache")
    private LoadingCache<String, TokenVersionCache> tokenValidVersionCache;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        long methodStartTime = System.currentTimeMillis();
        String requestURI = request.getRequestURI();

        try {
            log.info("拦截路径 {}", request.getRequestURI());

            if (!validateTokenPresence(request, response)) {
                return false;
            }

            String token = request.getHeader("authorization");
            String refreshToken = request.getHeader("Refresh-Token");

            response.setHeader("authorization", token);
            response.setHeader("Refresh-Token", refreshToken);

            try {
                Claims claims = jwtUtil.valiateAndGetClaimFromToken(token);
                return handleValidToken(request, response, claims, token, refreshToken);
            } catch (ExpiredJwtException e) {
                return handleExpiredToken(request, response, e);
            } catch (Exception e) {
                log.error(e.getMessage());
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return false;
            }
        } finally {
            long totalTime = System.currentTimeMillis() - methodStartTime;
            if (totalTime > 100) {
                log.warn("【性能告警】preHandle处理耗时过长: {} ms, URI: {}", totalTime, requestURI);
            }
        }
    }

    /**
     * 校验请求头中 token 和 Refresh-Token 是否都存在
     * 任一为空则直接返回 false
     */
    private boolean validateTokenPresence(HttpServletRequest request, HttpServletResponse response) {
        String token = request.getHeader("authorization");
        if (token == null) {
            log.info("token is null");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        if (request.getHeader("Refresh-Token") == null) {
            log.info("Refresh-Token is null");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        return true;
    }

    /**
     * 处理未过期的 JWT token
     * 1. 解析 claims 并保存用户信息到 ThreadLocal
     * 2. 通过本地 Caffeine 缓存快速校验版本号
     * 3. 判断 token 是否临期（剩余 < 5-10 分钟），临期则走 Lua 刷新
     */
    private boolean handleValidToken(HttpServletRequest request, HttpServletResponse response, Claims claims, String token, String clientRefreshToken) {
        if (!valiateClaimAndSaveUser(response, claims, token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        Long versionFromToken = claims.get("version", Long.class);
        Long userId = UserHolder.getUserId();

        if (!validateLocalVersionCache(userId, versionFromToken)) {
            log.info("本地缓存校验未通过，需走Redis校验 userId: {}", userId);
        }

        Date expiration = claims.getExpiration();
        long timeToExpire = expiration.getTime() - System.currentTimeMillis();
        long remaningTime = RandomUtil.randomLong(5L, 10L) * 60 * 1000;

        if (timeToExpire < remaningTime && timeToExpire > 0) {
            log.info("token has close deadline");
            return refreshDeadlineToken(request, response, token, clientRefreshToken, versionFromToken, userId);
        }

        log.info("Token还有效（剩余{}分钟），无需刷新", timeToExpire / (60 * 1000));
        return true;
    }

    /**
     * 通过本地 Caffeine 缓存快速校验 token 版本号是否有效
     * 缓存命中且版本匹配 → 返回 true（无需走 Redis）
     * 缓存未命中 / 版本不匹配 → 返回 false（需走 Redis 校验）
     */
    private boolean validateLocalVersionCache(Long userId, Long versionFromToken) {
        String versionKey = CaffeineConstants.TOKEN_VALID_VERSION_CACHE_KEY + userId;
        TokenVersionCache localCache = tokenValidVersionCache.getIfPresent(versionKey);

        if (localCache == null) {
            log.info("本地版本缓存未命中，userId: {}", userId);
            return false;
        }

        Integer status = localCache.getStatus();
        if (CaffeineConstants.TOKEN_VERSION_CACHE_HIT.equals(status)) {
            if (!localCache.getVersion().equals(versionFromToken)) {
                log.info("本地版本缓存过期，userId: {}, 缓存版本: {}, token版本: {}", userId, localCache.getVersion(), versionFromToken);
                return false;
            }
            log.info("本地版本缓存命中，userId: {}, version: {}", userId, versionFromToken);
            return true;
        }

        return false;
    }

    /**
     * 临期 token 刷新（剩余有效期 < 5-10 分钟）
     * 通过 RefreshToken.lua 原子性校验 refreshToken 和版本号，
     * 校验通过后生成新 token 并延长各 key 的过期时间
     */
    private boolean refreshDeadlineToken(HttpServletRequest request, HttpServletResponse response, String token, String clientRefreshToken, Long version, Long userId) {
        long refreshTokenStartTime = System.currentTimeMillis();

        String newToken = jwtUtil.generateToken(userId, RedisConstants.LOGIN_JWT_TTL_MINUTES, ChronoUnit.MINUTES, version);
        String tokenKey = RedisConstants.LOGIN_USER_KEY + userId;
        String versionKey = RedisConstants.LOGIN_VALID_VERSION_KEY + userId;
        String refreshKey = RedisConstants.LOGIN_REFRESH_USER_KEY + userId;
        String newVersionKey = RedisConstants.CURRENT_TOKEN_VERSION_KEY + userId;
        String userInfoKey = CaffeineConstants.USERINFO_CACHE_KEY + userId;

        UserinfoCache cache = userinfoCaffeine.getIfPresent(userInfoKey);
        boolean needGetUserInfoFromRedis = cache == null;
        String userinfoMapJson = needGetUserInfoFromRedis ? "" : JSONUtil.toJsonStr(cache);

        // ==================== RefreshToken.lua 参数说明 ====================
        // KEYS[1]: tokenKey     = "login:token:" + userId      (当前 access token)
        // KEYS[2]: versionKey   = "token:version:" + userId    (有效版本号)
        // KEYS[3]: refreshKey   = "refresh:user:" + userId     (refresh token)
        // KEYS[4]: newVersionKey = "token:version:current:" + userId (最新版本号 key)
        // KEYS[5]: userInfoKey  = "cache:userinfo:" + userId   (用户信息缓存)
        //
        // ARGV[1]: oldToken              (请求携带的旧 access token，与 Redis 比较)
        // ARGV[2]: newToken              (新生成的 access token，替换 Redis 中的旧值)
        // ARGV[3]: tokenExpireSeconds    (新 token 过期秒数，默认 30 分钟)
        // ARGV[4]: oldVersion            (从 token 中解析的旧版本号，与 Redis 比较)
        // ARGV[5]: clientRefreshToken    (请求携带的 refresh token，与 Redis 比较)
        // ARGV[6]: newVersionExpireSeconds (最新版本号 key 的过期秒数，默认 8 天)
        // ARGV[7]: refreshExpireSeconds  (refresh token 过期秒数，默认 7 天)
        // ARGV[8]: userinfoMapJson       (用户信息 JSON，空字符串表示需从 Redis 加载)
        // ARGV[9]: userinfoExpireSeconds (用户信息缓存过期秒数，默认 20 分钟)
        // ================================================================
        List<String> args = new ArrayList<>();
        args.add(token);
        args.add(newToken);
        args.add(String.valueOf(TimeUnit.MINUTES.toSeconds(RedisConstants.LOGIN_JWT_TTL_MINUTES)));
        args.add(version.toString());
        args.add(clientRefreshToken);
        args.add(RedisConstants.NEW_VERSION_TTL_SECONDS.toString());
        args.add(RedisConstants.LOGIN_REFRESHTOKEN_TTL_SECONDS.toString());
        args.add(userinfoMapJson);
        args.add(CaffeineConstants.USERINFO_CACHE_TTL_SECONDS.toString());

        List<String> keys = new ArrayList<>();
        keys.add(tokenKey);
        keys.add(versionKey);
        keys.add(refreshKey);
        keys.add(newVersionKey);
        keys.add(userInfoKey);

        long luaExecuteStartTime = System.currentTimeMillis();
        Long luaResult = stringRedisTemplate.execute(refreshDeadlineTokenScript, keys, args.toArray());
        log.info("【Lua脚本执行】耗时: {} ms", System.currentTimeMillis() - luaExecuteStartTime);

        if (TokenRefreshCode.SUCCESS.getCode().equals(luaResult)) {
            log.info("临期token刷新成功");
            updateLocalVersionCache(userId, version);
            response.setHeader("authorization", newToken);
            log.info("【刷新token总耗时】: {} ms", System.currentTimeMillis() - refreshTokenStartTime);
            return true;
        }

        if (TokenRefreshCode.USEINFO_NOT_FOUND.getCode().equals(luaResult) || TokenRefreshCode.USERINFO_CACHE_EMPTY.getCode().equals(luaResult)) {
            log.info("临期token刷新成功，用户信息需异步加载");
            if (cache != null) {
                batchLoadCache.saveFuture(cache.getId());
            }
            updateLocalVersionCache(userId, version);
            response.setHeader("authorization", newToken);
            log.info("【刷新token总耗时】: {} ms", System.currentTimeMillis() - refreshTokenStartTime);
            return true;
        }

        log.info("Refresh accessToken lua execute failed case:{}", TokenRefreshCode.getDefaultMessage(luaResult));
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        return false;
    }

    /**
     * token 刷新成功后，同步更新本地 Caffeine 版本缓存
     * 使后续请求可直接通过本地缓存快速校验，减少 Redis 访问
     */
    private void updateLocalVersionCache(Long userId, Long version) {
        String versionKey = CaffeineConstants.TOKEN_VALID_VERSION_CACHE_KEY + userId;
        TokenVersionCache tokenVersionCache = new TokenVersionCache();
        tokenVersionCache.setUserId(userId);
        tokenVersionCache.setVersion(version);
        tokenVersionCache.setStatus(CaffeineConstants.TOKEN_VERSION_CACHE_HIT);
        tokenValidVersionCache.put(versionKey, tokenVersionCache);
    }

    /**
     * 过期 token 刷新（JWT 已过期）
     * 通过 RefreshExpiredToken.lua 原子性校验 refreshToken 和版本号，
     * 校验通过后生成新 token、新 refreshToken、新版本号并更新 Redis
     */
    private boolean handleExpiredToken(HttpServletRequest request, HttpServletResponse response, ExpiredJwtException e) {
        long expiredTokenHandleStartTime = System.currentTimeMillis();

        String token = request.getHeader("authorization");

        boolean result = valiateClaimAndSaveUser(response, e.getClaims(), token);
        if (!result) {
            log.info("【过期token处理】耗时: {} ms", System.currentTimeMillis() - expiredTokenHandleStartTime);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        Long userId = UserHolder.getUserId();
        Long versionFromToken;
        try {
            versionFromToken = Long.valueOf(String.valueOf(e.getClaims().get("version")));
        } catch (NumberFormatException ex) {
            log.warn("无法从过期 token 中解析 version: {}", ex.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        String refreshToken = request.getHeader("Refresh-Token");
        if (refreshToken == null) {
            log.info("RefreshToken is null, failed to refresh token");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        String newRefreshToken = UUID.randomUUID().toString().replace("-", "");
        Long newVersion = redisIdWorker.nextVersion();
        String newToken = jwtUtil.generateToken(userId, RedisConstants.LOGIN_JWT_TTL_MINUTES, ChronoUnit.MINUTES, newVersion);

        String refreshKey = RedisConstants.LOGIN_REFRESH_USER_KEY + userId;
        String tokenKey = RedisConstants.LOGIN_USER_KEY + userId;
        String validVersionKey = RedisConstants.LOGIN_VALID_VERSION_KEY + userId;
        String newVersionKey = RedisConstants.CURRENT_TOKEN_VERSION_KEY + userId;
        Long newVersionExpireSeconds = RedisConstants.NEW_VERSION_TTL_SECONDS;

        // ==================== RefreshExpiredToken.lua 参数说明 ====================
        // KEYS[1]: refreshKey     = "refresh:user:" + userId      (refresh token)
        // KEYS[2]: tokenKey       = "login:token:" + userId       (当前 access token)
        // KEYS[3]: validVersionKey = "token:version:" + userId    (有效版本号)
        // KEYS[4]: newVersionKey  = "token:version:current:" + userId (最新版本号 key)
        //
        // ARGV[1]: oldRefreshToken          (请求携带的旧 refresh token，与 Redis 比较)
        // ARGV[2]: newRefreshToken          (新生成的 refresh token，替换 Redis 中的旧值)
        // ARGV[3]: refreshTokenExpireSeconds (新 refresh token 过期秒数，默认 7 天)
        // ARGV[4]: newToken                 (新生成的 access token，替换 Redis 中的旧值)
        // ARGV[5]: tokenExpireSeconds       (新 token 过期秒数，默认 30 分钟)
        // ARGV[6]: oldVersion               (从过期 JWT 中解析的旧版本号，与 Redis 比较)
        // ARGV[7]: versionExpireSeconds     (版本号过期秒数，与 refresh token 保持一致)
        // ARGV[8]: newVersion               (新生成的版本号，替换 Redis 中的旧版本号)
        // ARGV[9]: newVersionExpireSeconds  (最新版本号 key 的过期秒数，默认 8 天)
        // =====================================================================
        List<String> args = new ArrayList<>();
        args.add(refreshToken);
        args.add(newRefreshToken);
        args.add(String.valueOf(TimeUnit.DAYS.toSeconds(7)));
        args.add(newToken);
        args.add(String.valueOf(TimeUnit.MINUTES.toSeconds(RedisConstants.LOGIN_JWT_TTL_MINUTES)));
        args.add(versionFromToken.toString());
        args.add(RedisConstants.LOGIN_REFRESHTOKEN_TTL_SECONDS.toString());
        args.add(newVersion.toString());
        args.add(newVersionExpireSeconds.toString());

        List<String> keys = new ArrayList<>();
        keys.add(refreshKey);
        keys.add(tokenKey);
        keys.add(validVersionKey);
        keys.add(newVersionKey);

        Long luaResult = stringRedisTemplate.execute(refreshDeadTokenScript, keys, args.toArray());

        if (TokenRefreshCode.SUCCESS.getCode().equals(luaResult)) {
            log.info("过期token刷新成功");
            updateLocalVersionCache(userId, newVersion);
            response.setHeader("authorization", newToken);
            response.setHeader("Refresh-Token", newRefreshToken);
            log.info("【过期token处理】耗时: {} ms", System.currentTimeMillis() - expiredTokenHandleStartTime);
            return true;
        }

        log.info("Refresh token fail code: {}", TokenRefreshCode.getDefaultMessage(luaResult));
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        return false;
    }
    /**
     * 检查claim并提取UserId和UserDto塞入threadLocal
     * UserDto塞入redis中
     * @param response
     * @param claims
     * @param token
     * @return
     */
    private boolean valiateClaimAndSaveUser(HttpServletResponse response, Claims claims, String token) {
        if (claims == null || claims.isEmpty()) {
            return false;
        }

        Long userId;
        try {
            Object userIdObj = claims.get("userId");
            if (userIdObj == null) {
                log.error("userId not found in claims");
                return false;
            }
            if (userIdObj instanceof Number) {
                userId = ((Number) userIdObj).longValue();
            } else if (userIdObj instanceof String) {
                userId = Long.valueOf((String) userIdObj);
            } else {
                log.error("Unexpected userId type: {}", userIdObj.getClass());
                return false;
            }
        } catch (Exception e) {
            log.error("Failed to parse userId from claims", e);
            return false;
        }

        try {
            String userInfoKey = CaffeineConstants.USERINFO_CACHE_KEY + userId;
            UserHolder.saveUserId(userId);
            UserinfoCache cache = userinfoCaffeine.get(userInfoKey);
            UserHolder.saveUserDTO(cache);
        } catch (Exception e) {
            log.error("Failed to save to ThreadLocal for userId: {}", userId, e);
            return false;
        }

        log.info("User validation and save completed for userId: {}", userId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.remove();
        log.info("用户信息已清除");
    }
}
