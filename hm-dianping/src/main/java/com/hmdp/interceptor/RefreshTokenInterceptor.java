package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.LuaResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.utils.JwtUtil;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private static final JwtUtil JWT_UTIL = new JwtUtil(new DefaultResourceLoader());

    private StringRedisTemplate stringRedisTemplate;
    private IUserService userService;
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate, IUserService userService,RedisIdWorker redisIdWorker) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.userService = userService;
        this.redisIdWorker = redisIdWorker;
    }
    private static final DefaultRedisScript<String > REDIS_REFRESH_TOKEN_SCRIPT;
    private static final DefaultRedisScript<String> REDIS_REFRESH_REFRESH_TOKEN_SCRIPT;
    private RedisIdWorker redisIdWorker;
    static {
        REDIS_REFRESH_TOKEN_SCRIPT = new DefaultRedisScript<>();
        REDIS_REFRESH_TOKEN_SCRIPT.setResultType(String.class);
        REDIS_REFRESH_TOKEN_SCRIPT.setLocation(new ClassPathResource("RefreshToken.lua"));
        REDIS_REFRESH_REFRESH_TOKEN_SCRIPT = new DefaultRedisScript<>();
        REDIS_REFRESH_REFRESH_TOKEN_SCRIPT.setResultType(String.class);
        REDIS_REFRESH_REFRESH_TOKEN_SCRIPT.setLocation(new ClassPathResource("RefreshRefreshToken.lua"));
    }
//    @Override
//    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        String uri = request.getRequestURI();
//        if (uri.equals("/blog/hot") || uri.startsWith("/blog/hot")
//                || uri.equals("/user/login")
//                || uri.equals("/user/code")
//                || uri.equals("/shop-type/list")) {
//            log.info("公开路径，直接放行: {}", uri);
//            return true;
//        }
//        log.info("拦截路径 {}", request.getRequestURI());
//        String token = request.getHeader("authorization");
//        if (token == null) {
//            log.info("token is null");
//            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
//            return false;
//        }
//        Claims claims;
//        try {
//            claims = JWT_UTIL.valiateAndGetClaimFromToken(token);
//            log.info("解析token成功,{}未过期", claims);
//            //直接刷新token即可，顺便塞入threadlocal
//            //不需要刷新refreshToken
//            if(valiateClaimAndSaveUser(response,  claims,token)){
//                String newToken = JWT_UTIL.generateToken(UserHolder.getUserId(),30L,ChronoUnit.MINUTES);
//                String tokenKey = RedisConstants.LOGIN_USER_KEY + UserHolder.getUserId();
//                List<String> args = new ArrayList<>();
//                args.add(token);
//                args.add(newToken);
//                args.add("1800");
//                List<String> keys = new ArrayList<>();
//                keys.add(tokenKey);
//                String  execute = stringRedisTemplate.execute(REDIS_REFRESH_TOKEN_SCRIPT, keys, args.toArray());
//                LuaResult luaResult = JSONUtil.toBean(execute, LuaResult.class);
//                if(luaResult.getCode() == 0){
//                    log.info("Refresh accessToken lua execute failed case:{}",luaResult.getMessage());
//                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
//                    return false;
//                }
//                log.info("set token {}",luaResult.getMessage());
//                response.setHeader("authorization",newToken);
//                return true;
//            }
//            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
//            return false;
//        } catch (ExpiredJwtException e) {
//            //TODO 用户信息需要存入上下文
//            //过期的情况下redis中不存在对应token，应该直接刷新一个新token
//           response = handleExpiredToken(request, response, e);
//           if(response.getStatus() == HttpServletResponse.SC_UNAUTHORIZED){
//               log.info("token update failed");
//               return false;
//           }
//           boolean result = valiateClaimAndSaveUser(response,e.getClaims(),token);
//           if(!result){
//               response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
//               return false;
//           }
//        } catch (Exception e) {
//            log.error(e.getMessage());
//            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
//            return false;
//        }
//        return true;
//    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();
        if (uri.equals("/blog/hot") || uri.startsWith("/blog/hot")
                || uri.equals("/user/login")
                || uri.equals("/user/code")
                || uri.equals("/shop-type/list")) {
            log.info("公开路径，直接放行: {}", uri);
            return true;
        }

        log.info("拦截路径 {}", request.getRequestURI());
        String token = request.getHeader("authorization");
        if (token == null) {
            log.info("token is null");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        Claims claims;
        try {
            claims = JWT_UTIL.valiateAndGetClaimFromToken(token);
            log.info("解析token成功,{}未过期", claims);
            // 1. 先验证用户信息并保存到 ThreadLocal
            if(valiateClaimAndSaveUser(response, claims, token)){
                // 2. 检查token是否需要刷新（剩余时间 < 10分钟）
                Date expiration = claims.getExpiration();
                long now = System.currentTimeMillis();
                long timeToExpire = expiration.getTime() - now;
                long tenMinutes = 10 * 60 * 1000; // 10分钟
                if (timeToExpire < tenMinutes && timeToExpire > 0) {
                    // 3. 只有在token快要过期时才刷新
                    log.info("token has expired");
                    Long version = claims.get("version", Long.class);
                    //正常刷新的token不修改version
                    String newToken = JWT_UTIL.generateToken(UserHolder.getUserId(), RedisConstants.LOGIN_JWT_TTL_MINUTES, ChronoUnit.MINUTES,version);
                    String tokenKey = RedisConstants.LOGIN_USER_KEY + UserHolder.getUserId();
                    String versionKey = RedisConstants.LOGIN_VALID_VERSION_KEY + UserHolder.getUserId();
                    List<String> args = new ArrayList<>();
                    args.add(token);
                    args.add(newToken);
                    args.add(String.valueOf((60*RedisConstants.LOGIN_JWT_TTL_MINUTES)));
//                    args.add(version.toString());
                    List<String> keys = new ArrayList<>();
                    keys.add(tokenKey);
//                    keys.add(versionKey);
                    String execute = stringRedisTemplate.execute(REDIS_REFRESH_TOKEN_SCRIPT, keys, args.toArray());
                    LuaResult luaResult = JSONUtil.toBean(execute, LuaResult.class);
                    if(luaResult.getCode() == 0){
                        log.info("Refresh accessToken lua execute failed case:{}", luaResult.getMessage());
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        return false;
                    }
                    log.info("set token {}", luaResult.getMessage());
                    response.setHeader("authorization", newToken);
                } else {
                    log.info("Token还有效（剩余{}分钟），无需刷新", timeToExpire / (60 * 1000));
                }
                return true;
            }

            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;

        } catch (ExpiredJwtException e) {
            //TODO 用户信息需要存入上下文
            //过期的情况下redis中不存在对应token，应该直接刷新一个新token
            boolean result = valiateClaimAndSaveUser(response, e.getClaims(), token);
            if(!result){
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return false;
            }
            response = handleExpiredToken(request, response, e);
            if(response.getStatus() == HttpServletResponse.SC_UNAUTHORIZED){
                log.info("token update failed");
                return false;
            }
            if(response.getStatus() == HttpServletResponse.SC_NOT_ACCEPTABLE){
                log.error("token can not update");
                return false;
            }

            return true;  // 添加这一行，异常处理成功后返回true

        } catch (Exception e) {
            log.error(e.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
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

        log.info("claims {}", claims);

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
            log.info("userId: {} (original type: {})", userId, userIdObj.getClass());
        } catch (Exception e) {
            log.error("Failed to parse userId from claims", e);
            return false;
        }

        String userInfoKey = RedisConstants.LOGIN_USERINFO_MAP + userId;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(userInfoKey);

        UserDTO userDTO;

        if (userMap == null || userMap.isEmpty()) {
            log.info("Cache miss for userId: {}, querying database", userId);
            User user;
            try {
                user = userService.getById(userId);
            } catch (Exception e) {
                log.error("Database query failed for userId: {}", userId, e);
                return false;
            }

            if (user == null) {
                log.warn("User not found in database: {}", userId);
                try {
                    stringRedisTemplate.opsForHash().put(userInfoKey, "null", "true");
                    stringRedisTemplate.expire(userInfoKey, 5, TimeUnit.MINUTES);
                } catch (Exception e) {
                    log.error("Failed to set null cache for userId: {}", userId, e);
                }
                return false;
            }

            // 从数据库查询到用户，构建 UserDTO
            userDTO = new UserDTO();
            userDTO.setId(userId);
            userDTO.setNickName(user.getNickName());
            userDTO.setIcon(user.getIcon());

            // 缓存到 Redis - 保持和 login 方法一致，所有值转成 String
            try {
                Map<String, Object> userDtoMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                        CopyOptions.create().setIgnoreNullValue(true)
                                .setFieldValueEditor((fieldName, fieldValue) ->
                                        fieldValue != null ? fieldValue.toString() : null));

                stringRedisTemplate.opsForHash().putAll(userInfoKey, userDtoMap);
                log.info("Cached user info for userId: {}", userId);
            } catch (Exception e) {
                log.error("Failed to cache user info for userId: {}", userId, e);
                return false;
            }
        } else {
            // 检查是否是空值缓存
            if (userMap.containsKey("null") && "true".equals(userMap.get("null"))) {
                log.warn("User is null-cached: {}", userId);
                return false;
            }

            // 从 Redis 缓存读取 - 兼容所有值都是 String 的情况
            try {
                userDTO = new UserDTO();
                userDTO.setId(userId);  // 直接使用 userId，不从 map 读取

                // 从 map 获取值，所有值都是 String 类型
                Object nickNameObj = userMap.get("nickName");
                userDTO.setNickName(nickNameObj != null ? nickNameObj.toString() : null);

                Object iconObj = userMap.get("icon");
                userDTO.setIcon(iconObj != null ? iconObj.toString() : null);

                log.info("Retrieved user from cache: {}", userDTO);
            } catch (Exception e) {
                log.error("Failed to convert userMap to UserDTO for userId: {}", userId, e);
                return false;
            }
        }

        // 保存到 ThreadLocal
        try {
            UserHolder.saveUserId(userId);
            UserHolder.saveUserDTO(userDTO);
            log.info("Saved to ThreadLocal - userId: {}, userDTO: {}", userId, userDTO);
        } catch (Exception e) {
            log.error("Failed to save to ThreadLocal for userId: {}", userId, e);
            return false;
        }

        // 刷新缓存过期时间
        try {
            stringRedisTemplate.expire(userInfoKey, 30, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Failed to set expiration for userId: {}", userId, e);
            // 不影响主要逻辑，继续执行
        }

        log.info("User validation and save completed for userId: {}", userId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.remove();
        log.info("用户信息已清除");
    }
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
            log.info("从过期 token 中提取 userId: {}", userId);
            versionFromToken = Long.valueOf(String.valueOf(e.getClaims().get("version").toString()));
            log.info("从过期 token 中提取 version: {}", versionFromToken);
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
        //发来的请求没refreshToken那不就是假的？
        token = JWT_UTIL.generateToken(userId, RedisConstants.LOGIN_JWT_TTL_MINUTES, ChronoUnit.MINUTES,newVersion);
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
        //refreshToken设置为7天的过期时间
        // 判断是否存在 refresh token
        List<String> keys = new ArrayList<>();
        keys.add(refreshKey);
        keys.add(tokenKey);
        keys.add(versionKey);
        String execute = stringRedisTemplate.execute(REDIS_REFRESH_REFRESH_TOKEN_SCRIPT, keys, args.toArray());
        LuaResult luaResult = JSONUtil.toBean(execute, LuaResult.class);
        if(luaResult.getCode() == 0){
            log.info("Refresh all token lua execute failed cause:{}",luaResult.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return response;
        }
        if(luaResult.getCode() == 1){
            log.info("update expired token ,{}",luaResult.getMessage());
        }
        if(luaResult.getCode() == 2){
            log.error("{}",luaResult.getMessage());
            response.setStatus(HttpServletResponse.SC_NOT_ACCEPTABLE);
            response.setHeader("authorization", request.getHeader("authorization"));
            response.setHeader("Refresh-Token", request.getHeader("refresh-token"));
            return response;
        }
        response.setHeader("authorization",token);
        response.setHeader("Refresh-Token",newRefreshToken);
        return response;
    }
//        boolean existed = stringRedisTemplate.hasKey(refreshKey);
//        if (!existed) {
//            log.info("用户{} redis 不存在对应的refreshToken",userId);
//            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
//            return false;
//        } else {
//            // 已存在 → 检查是否为redis中的，-》删除旧的，重新生成新的（强制覆盖，实现单点登录）
//            String oldRefreshToken = stringRedisTemplate.opsForValue().get(refreshKey);
//            if (!refreshToken.equals(oldRefreshToken)) {
//                log.info("refreshToken {} is old", refreshToken);
//                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
//                return false;
//            }
//            stringRedisTemplate.delete(refreshKey);
//            stringRedisTemplate.opsForValue().set(refreshKey, newRefreshToken, 7, TimeUnit.DAYS);
//            log.debug("为用户 {} 刷新 refresh token（旧 token 已失效）", userId);
    //下面这段应该不需要，未过期的走正常请求了
//            if(stringRedisTemplate.hasKey(RedisConstants.LOGIN_USER_KEY + userId)){
//                log.info("未过期的token泄露");
//                return false;
//            }
////        }
//        response.setHeader("authorization", token);
//        response.setHeader("Refresh-Token", newRefreshToken);
}
