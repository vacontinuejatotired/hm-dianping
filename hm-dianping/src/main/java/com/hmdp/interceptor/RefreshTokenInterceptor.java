package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.hmdp.Enum.TokenRefreshCode;
import com.hmdp.entity.User;
import com.hmdp.entity.UserinfoCache;
import com.hmdp.interceptor.annotation.RecordTime;
import com.hmdp.service.IUserService;
import com.hmdp.utils.*;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import com.github.benmanes.caffeine.cache.Cache;           // 缓存接口

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @author Ntwitm
 */
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


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 整个方法开始计时
        long methodStartTime = System.currentTimeMillis();
        String requestURI = request.getRequestURI();

        try {
            // 公开路径检查 - 开始计时
            long publicPathStartTime = System.currentTimeMillis();
            String uri = request.getRequestURI();
            if (uri.equals("/blog/hot") || uri.startsWith("/blog/hot")
                    || uri.equals("/user/login")
                    || uri.equals("/user/code")
                    || uri.equals("/shop-type/list")) {
                log.info("【公开路径检查】耗时: {} ms", System.currentTimeMillis() - publicPathStartTime);
                log.info("【preHandle总耗时】: {} ms", System.currentTimeMillis() - methodStartTime);
                return true;
            }

            log.info("拦截路径 {}", request.getRequestURI());


            // Token空值检查 - 开始计时
            long tokenNullCheckStartTime = System.currentTimeMillis();
            String token = request.getHeader("authorization");
            if (token == null) {
                log.info("token is null");
                log.info("【Token空值检查】耗时: {} ms", System.currentTimeMillis() - tokenNullCheckStartTime);
                log.info("【preHandle总耗时】: {} ms", System.currentTimeMillis() - methodStartTime);
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED, "token is null");
                return false;
            }
            if (request.getHeader("Refresh-Token") == null) {
                log.info("Refresh-Token is null");
                log.info("【Token空值检查】耗时: {} ms", System.currentTimeMillis() - tokenNullCheckStartTime);
                log.info("【preHandle总耗时】: {} ms", System.currentTimeMillis() - methodStartTime);
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED, "Refresh-Token is null");
                return false;
            }

            Claims claims;
            try {
                // Token解析 - 开始计时
                response.setHeader("authorization", token);
                response.setHeader("Refresh-Token", request.getHeader("Refresh-Token"));
                claims = jwtUtil.valiateAndGetClaimFromToken(token);
                // 验证用户信息 - 开始计时
                long validateUserStartTime = System.currentTimeMillis();
                if (valiateClaimAndSaveUser(response, claims, token)) {
                    Date expiration = claims.getExpiration();
                    long now = System.currentTimeMillis();
                    long timeToExpire = expiration.getTime() - now;
                    long tenMinutes = 10 * 60 * 1000;
                    if (timeToExpire < tenMinutes && timeToExpire > 0) {
                        log.info("token has close deadline");

                        // 刷新token流程 - 开始计时
                        long refreshTokenStartTime = System.currentTimeMillis();

                        Long version = claims.get("version", Long.class);
                        String newToken = jwtUtil.generateToken(UserHolder.getUserId(), RedisConstants.LOGIN_JWT_TTL_MINUTES, ChronoUnit.MINUTES, version);
                        String tokenKey = RedisConstants.LOGIN_USER_KEY + UserHolder.getUserId();
                        String versionKey = RedisConstants.LOGIN_VALID_VERSION_KEY + UserHolder.getUserId();
                        String refreshKey = RedisConstants.LOGIN_REFRESH_USER_KEY + UserHolder.getUserId();
                        String clientRefreshToken = request.getHeader("Refresh-Token");
                        String newVersionKey = RedisConstants.CURRENT_TOKEN_VERSION_KEY + UserHolder.getUserId();
                        String userInfoKey = CaffeineConstants.USERINFO_CACHE_KEY + UserHolder.getUserId();
                        Long newVersionExpireTime = RedisConstants.NEW_VERSION_TTL_SECONDS;
                        Long refreshTokenAndValidVersionExpireSeconds = RedisConstants.LOGIN_REFRESHTOKEN_TTL_SECONDS;
                        Long userinfoExpireSeconds = CaffeineConstants.USERINFO_CACHE_TTL_SECONDS;
                        boolean needGetUserInfoFromRedis = false;
                        //本地cache未查询到则准备在后续lua脚本中查询
                        UserinfoCache cache = userinfoCaffeine.getIfPresent(userInfoKey);
                        if (cache == null) {
                            needGetUserInfoFromRedis = true;
                        }
                         String userinfoMapJson = "";
                         if(needGetUserInfoFromRedis){
                             userinfoMapJson = cache != null ? JSONUtil.toJsonStr(cache) : "";
                         }
                        List<String> args = new ArrayList<>();
                        args.add(token);
                        args.add(newToken);
                        args.add(String.valueOf((60 * RedisConstants.LOGIN_JWT_TTL_MINUTES)));
                        args.add(version.toString());
                        args.add(clientRefreshToken);
                        args.add(newVersionExpireTime.toString());
                        args.add(refreshTokenAndValidVersionExpireSeconds.toString());
                        args.add(userinfoMapJson);
                        args.add(userinfoExpireSeconds.toString());

                        List<String> keys = new ArrayList<>();
                        keys.add(tokenKey);
                        keys.add(versionKey);
                        keys.add(refreshKey);
                        keys.add(newVersionKey);
                        keys.add(userInfoKey);
                        // Lua脚本执行 - 开始计时
                        long luaExecuteStartTime = System.currentTimeMillis();
                        Long luaResult = stringRedisTemplate.execute(refreshDeadlineTokenScript, keys, args.toArray());
                        log.info("【Lua脚本执行】耗时: {} ms", System.currentTimeMillis() - luaExecuteStartTime);
                        if (!luaResult.equals(TokenRefreshCode.SUCCESS.getCode())||!luaResult.equals(TokenRefreshCode.USEINFO_NOT_FOUND.getCode())||!luaResult.equals(TokenRefreshCode.USERINFO_CACHE_EMPTY)) {
                            log.info("Refresh accessToken lua execute failed case:{}", TokenRefreshCode.getDefaultMessage(luaResult));
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            return false;
                        }
                        if(luaResult.equals(TokenRefreshCode.USEINFO_NOT_FOUND.getCode())){
                            batchLoadCache.saveFuture(cache.getId());
                        }
                        log.info("set token {}", TokenRefreshCode.getDefaultMessage(luaResult));
                        response.setHeader("authorization", newToken);
                        log.info("【刷新token总耗时】: {} ms", System.currentTimeMillis() - refreshTokenStartTime);
                    } else {
                        log.info("Token还有效（剩余{}分钟），无需刷新", timeToExpire / (60 * 1000));
                        return true;
                    }
                    return true;
                }
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return false;
            } catch (ExpiredJwtException e) {
                // 过期token处理 - 开始计时
                long expiredTokenHandleStartTime = System.currentTimeMillis();
                boolean result = valiateClaimAndSaveUser(response, e.getClaims(), token);
                if (!result) {
                    log.info("【过期token处理】耗时: {} ms", System.currentTimeMillis() - expiredTokenHandleStartTime);
                    log.info("【preHandle总耗时】: {} ms", System.currentTimeMillis() - methodStartTime);
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return false;
                }
                response = handleExpiredToken(request, response, e);
                log.info("【过期token处理】耗时: {} ms", System.currentTimeMillis() - expiredTokenHandleStartTime);

                if (response.getStatus() == HttpServletResponse.SC_UNAUTHORIZED) {
                    log.info("token update failed");
                    return false;
                }
                if (response.getStatus() == HttpServletResponse.SC_NOT_ACCEPTABLE) {
                    log.error("token can not update");
                    return false;
                }
                return true;
            } catch (Exception e) {
                log.error(e.getMessage());
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return false;
            }
        } finally {
            // 最终总耗时统计
            long totalTime = System.currentTimeMillis() - methodStartTime;
            if (totalTime > 100) {
                log.warn("【性能告警】preHandle处理耗时过长: {} ms, URI: {}", totalTime, requestURI);
            }
        }
    }
//
//    private UserinfoCache loadUserinfoFromCache(Long userId) {
//        if (userId == null) {
//            return null;
//        }
//
//        String caffeineKey = CaffeineConstants.USERINFO_CACHE_KEY + userId;
//        String redisKey = RedisConstants.LOGIN_USERINFO_MAP + userId;
//
//        // 1. 一级缓存：Caffeine
//        UserinfoCache userinfoCache = userinfoCaffeine.get(caffeineKey,key->{
//            UserinfoCache tempCache = new UserinfoCache();
//            log.debug("Caffeine miss for user: {}, trying Redis", userId);
//
//            // 2. 二级缓存：Redis
//            Map<Object, Object> redisMap = stringRedisTemplate.opsForHash().entries(redisKey);
//            if (!redisMap.isEmpty()) {
//                tempCache = new UserinfoCache();
//                tempCache.setUserId(userId);
//
//                // 从Redis Map中提取数据
//                Object nickName = redisMap.get("nickName");
//                if (nickName != null) {
//                    tempCache.setNickName(nickName.toString());
//                }
//
//                Object icon = redisMap.get("icon");
//                if (icon != null) {
//                    tempCache.setIcon(icon.toString());
//                }
//
//                // 存入Caffeine
//                userinfoCaffeine.put(caffeineKey, tempCache);
//                log.info("Redis hit for user: {}, cached to Caffeine", userId);
//                return tempCache;
//            }
//
//            // 3. 三级存储：MySQL（异步加载）
//            log.info("Redis miss for user: {}, triggering async DB load", userId);
//
//            // 异步加载数据库
//            CompletableFuture.runAsync(()->{
//                asyncSaveInfoFromMysqlToCache(redisKey, userId);
//            }).exceptionally(throwable -> {
//                log.info("asyncSaveInfoFromMysqlToCache error: {}", throwable.getMessage());
//                return null;
//            });
//
//
//            // 返回一个临时对象（避免NPE）
//            tempCache.setUserId(userId);
//            tempCache.setIcon("");
//            tempCache.setNickName("");
//            return tempCache;
//        });
//        if (userinfoCache != null) {
//            log.debug("Caffeine hit for user: {}", userId);
//            return userinfoCache;
//        }
//        return userinfoCache;
//    }

    /**
     * 异步从mysql查数据并塞入redis以及本地缓存
     */
//    public void asyncSaveInfoFromMysqlToCache(String redisKey, Long userId){
//        User user = new User().setId(userId);
//        UserinfoCache userinfoCache;
//        try {
//            user = userService.getById(userId);
//            if(user == null){
//                //存空值
//                userinfoCache = new UserinfoCache(userId,"","");
//            }
//            else{
//                userinfoCache = new UserinfoCache(user.getId(),user.getNickName(),user.getIcon());
//            }
//            Map<String, Object> userInfoMap = BeanUtil.beanToMap(userinfoCache, new HashMap<>(),
//                    CopyOptions.create().setIgnoreNullValue(true)
//                            .setFieldValueEditor((fieldName, fieldValue) ->
//                                    fieldValue != null ? fieldValue.toString() : ""));
//            stringRedisTemplate.opsForHash().putAll(redisKey, userInfoMap);
//            stringRedisTemplate.expire(redisKey, CaffeineConstants.USERINFO_CACHE_TTL_MINUTES, TimeUnit.MINUTES);
//            userinfoCaffeine.put(redisKey,userinfoCache);
//            log.info("已缓存用户{}", userinfoCache);
//        } catch (Exception e) {
//            log.error("数据库查询用户信息失败 :{}",e.getMessage());
//        }
//
//    }

    /**
     * 刷新过期token以及redis中的refreshToken
     * 需自己在方法外重新设置请求头，该方法内修改请求头无效
     * redis中不存在的refreshToken不会刷新
     * @param request
     * @param response
     * @param e
     * @return
     */
    public HttpServletResponse handleExpiredToken(HttpServletRequest request, HttpServletResponse response, ExpiredJwtException e)  {
        String token = request.getHeader("authorization");
        log.info("token  expired");
        //我恨你 Long userId = (Long) e.getClaims().get("userId")
        Long userId,versionFromToken;
        try {
            userId = Long.valueOf(String.valueOf(e.getClaims().get("userId").toString()));
            versionFromToken = Long.valueOf(String.valueOf(e.getClaims().get("version").toString()));
        } catch (NumberFormatException ex) {
            log.warn("无法从过期 token 中解析 userId: {}", ex.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return response;
        }
        //检查是否携带refreshToken
        // Redis 中 refresh token 的 key
        String refreshKey = RedisConstants.LOGIN_REFRESH_USER_KEY + userId;
        String tokenKey = RedisConstants.LOGIN_USER_KEY + userId;
        String refreshToken = request.getHeader("Refresh-Token");
        String newRefreshToken = UUID.randomUUID().toString().replace("-", "");
        String versionKey = RedisConstants.LOGIN_VALID_VERSION_KEY + userId;
        Long newVersion = redisIdWorker.nextVersion();
        String newVersionKey = RedisConstants.CURRENT_TOKEN_VERSION_KEY + userId;
        Long newVersionExpireSeconds = RedisConstants.NEW_VERSION_TTL_SECONDS;
        //发来的请求没refreshToken那不就是假的？
        token = jwtUtil.generateToken(userId, RedisConstants.LOGIN_JWT_TTL_MINUTES, ChronoUnit.MINUTES,newVersion);
        if (refreshToken == null) {
            log.info("RefreshToken is null,failed to refresh token");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return response;
        }
        List<String >args = new ArrayList<>();
        args.add(refreshToken);
        args.add(newRefreshToken);
        args.add(String.valueOf(TimeUnit.DAYS.toSeconds(7)));
        args.add(token);
        args.add(String.valueOf(TimeUnit.MINUTES.toSeconds(RedisConstants.LOGIN_JWT_TTL_MINUTES)));
        args.add(versionFromToken.toString());
        args.add(RedisConstants.LOGIN_REFRESHTOKEN_TTL_SECONDS.toString());
        args.add(newVersion.toString());
        args.add(newVersionExpireSeconds.toString());
        //refreshToken设置为7天的过期时间
        // 判断是否存在 refresh token
        List<String> keys = new ArrayList<>();
        keys.add(refreshKey);
        keys.add(tokenKey);
        keys.add(versionKey);
        keys.add(newVersionKey);
        Long luaResult = stringRedisTemplate.execute(refreshDeadTokenScript, keys, args.toArray());
        if(luaResult.equals(TokenRefreshCode.SUCCESS.getCode())) {
            log.info("Refresh token success");
        }
        else{
            log.info("Refresh token fail code: {}", TokenRefreshCode.getDefaultMessage(luaResult));
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return response;
        }
        response.setHeader("authorization",token);
        response.setHeader("Refresh-Token",newRefreshToken);
        return response;
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
        // 安全获取 userId - 兼容 Number 和 String 类型
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

//        String userInfoKey = RedisConstants.LOGIN_USERINFO_MAP + userId;
//        //TODO50ms
//        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(userInfoKey);
//
//        UserDTO userDTO;
//
//        if (userMap == null || userMap.isEmpty()) {
//            log.info("Cache miss for userId: {}, querying database", userId);
//            User user;
//            try {
//                user = userService.getById(userId);
//            } catch (Exception e) {
//                log.error("Database query failed for userId: {}", userId, e);
//                return false;
//            }
//
//            if (user == null) {
//                log.warn("User not found in database: {}", userId);
//                try {
//                    stringRedisTemplate.opsForHash().put(userInfoKey, "null", "true");
//                    stringRedisTemplate.expire(userInfoKey, 5, TimeUnit.MINUTES);
//                } catch (Exception e) {
//                    log.error("Failed to set null cache for userId: {}", userId, e);
//                }
//                return false;
//            }
//
//            // 从数据库查询到用户，构建 UserDTO
//            userDTO = new UserDTO();
//            userDTO.setId(userId);
//            userDTO.setNickName(user.getNickName());
//            userDTO.setIcon(user.getIcon());
//
//            // 缓存到 Redis - 保持和 login 方法一致，所有值转成 String
//            //先放本地缓存，减少网络io，后续可以考虑异步刷新到redis
//            try {
//                Map<String, Object> userDtoMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
//                        CopyOptions.create().setIgnoreNullValue(true)
//                                .setFieldValueEditor((fieldName, fieldValue) ->
//                                        fieldValue != null ? fieldValue.toString() : null));
//
//                stringRedisTemplate.opsForHash().putAll(userInfoKey, userDtoMap);
//            } catch (Exception e) {
//                log.error("Failed to cache user info for userId: {}", userId, e);
//                return false;
//            }
//        } else {
//            // 检查是否是空值缓存
//            if (userMap.containsKey("null") && "true".equals(userMap.get("null"))) {
//                log.warn("User is null-cached: {}", userId);
//                return false;
//            }
//
//            // 从 Redis 缓存读取 - 兼容所有值都是 String 的情况
//            try {
//                userDTO = new UserDTO();
//                userDTO.setId(userId);  // 直接使用 userId，不从 map 读取
//
//                // 从 map 获取值，所有值都是 String 类型
//                Object nickNameObj = userMap.get("nickName");
//                userDTO.setNickName(nickNameObj != null ? nickNameObj.toString() : null);
//
//                Object iconObj = userMap.get("icon");
//                userDTO.setIcon(iconObj != null ? iconObj.toString() : null);
//            } catch (Exception e) {
//                log.error("Failed to convert userMap to UserDTO for userId: {}", userId, e);
//                return false;
//            }
//        }

        // 保存到 ThreadLocal
        try {
            String userInfoKey = CaffeineConstants.USERINFO_CACHE_KEY + userId;
            UserHolder.saveUserId(userId);
            UserinfoCache cache = userinfoCaffeine.get(userInfoKey);
            UserHolder.saveUserDTO(cache);
        } catch (Exception e) {
            log.error("Failed to save to ThreadLocal for userId: {}", userId, e);
            return false;
        }
//这段逻辑打算放在刷新临期token的lua脚本里执行，减少网络io，不然每一个请求都要多50ms，
//        // 刷新缓存过期时间
//        try {
//            //TODO50ms
//            stringRedisTemplate.expire(userInfoKey, 30, TimeUnit.MINUTES);
//        } catch (Exception e) {
//            log.warn("Failed to set expiration for userId: {}", userId, e);
//            // 不影响主要逻辑，继续执行
//        }

        log.info("User validation and save completed for userId: {}", userId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.remove();
        log.info("用户信息已清除");
    }
}
