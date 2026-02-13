package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.LuaResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.JwtUtil;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import io.jsonwebtoken.ExpiredJwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private static final JwtUtil JWT_UTIL = new JwtUtil(new DefaultResourceLoader());
    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final DefaultRedisScript<String > REDIS_REFRESH_TOKEN_SCRIPT;
    private static final DefaultRedisScript<String> REDIS_REFRESH_REFRESH_TOKEN_SCRIPT;

    static {
        REDIS_REFRESH_TOKEN_SCRIPT = new DefaultRedisScript<>();
        REDIS_REFRESH_TOKEN_SCRIPT.setResultType(String.class);
        REDIS_REFRESH_TOKEN_SCRIPT.setLocation(new ClassPathResource("RefreshToken.lua"));
        REDIS_REFRESH_REFRESH_TOKEN_SCRIPT = new DefaultRedisScript<>();
        REDIS_REFRESH_REFRESH_TOKEN_SCRIPT.setResultType(String.class);
        REDIS_REFRESH_REFRESH_TOKEN_SCRIPT.setLocation(new ClassPathResource("RefreshRefreshToken.lua"));
    }
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
        Map claims = null;
        try {
            claims = JWT_UTIL.valiateAndGetClaimFromToken(token);
            log.info("解析token成功,{}未过期", claims);
            //直接刷新token即可，顺便塞入threadlocal
            //不需要刷新refreshToken
            if(valiateClaimAndSaveUser(response,claims,token)){
                String newToken = JWT_UTIL.generateToken(UserHolder.getUserId(),30L,ChronoUnit.MINUTES);
                String tokenKey = RedisConstants.LOGIN_USER_KEY + UserHolder.getUserId();
                List<String> args = new ArrayList<>();
                args.add(token);
                args.add(newToken);
                args.add("1800");
                List<String> keys = new ArrayList<>();
                keys.add(tokenKey);
                String  execute = stringRedisTemplate.execute(REDIS_REFRESH_TOKEN_SCRIPT, keys, args.toArray());
                LuaResult luaResult = JSONUtil.toBean(execute, LuaResult.class);
                if(luaResult.getCode() == 0){
                    log.info("Refresh accessToken lua execute failed case:{}",luaResult.getMessage());
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return false;
                }
                log.info("set token {}",luaResult.getMessage());
                response.setHeader("authorization",newToken);
                return true;
            }
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        } catch (ExpiredJwtException e) {
            //TODO 用户信息需要存入上下文
            //过期的情况下redis中不存在对应token，应该直接刷新一个新token
           response = handleExpiredToken(request, response, e);
           if(response.getStatus() == HttpServletResponse.SC_UNAUTHORIZED){
               log.info("token update failed");
               return false;
           }
        } catch (Exception e) {
            log.error(e.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        return true;
    }

    /**
     * 检查claim并提取UserId和UserDto塞入threadLocal
     * 判断传入token是否在redis存在
     * 判断两个token是否相同
     * UserDto塞入redis中
     * @param response
     * @param claims
     * @param token
     * @return
     */
    private boolean valiateClaimAndSaveUser(HttpServletResponse response, Map claims, String token) {
        if (claims == null || claims.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        Long userId = Long.valueOf(String.valueOf(claims.get("userId")));
        //防止拿未过期的jwt蒙混
        //暂时不考虑设备问题
        //同一时刻只允许一个在线账号
//        if(!stringRedisTemplate.hasKey(RedisConstants.LOGIN_USER_KEY + userId)){
//            log.info("该token不存在");
//            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
//            return false;
//        }
//        if(!token.equals(stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_USER_KEY + userId))) {
//            log.info("请求携带token不可用");
//            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
//            return false;
//        }
        Map userMap=stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USERINFO_MAP+userId);
        UserDTO userDTO = BeanUtil.copyProperties(userMap, UserDTO.class);
        UserHolder.saveUserId(userId);
        UserHolder.saveUserDTO(userDTO);
        log.info("save UserDTO and UserId");
        stringRedisTemplate.expire(RedisConstants.LOGIN_USERINFO_MAP + userId, 30, TimeUnit.MINUTES);
        //处理jwt过期逻辑
        log.info("update user info");
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.remove();
    }
//            // 假设 userMap 来自登录验证结果
//            log.info("token {} expired", token);
//            Long userId = (Long) e.getClaims().get("userId");
//// 记录登录事件（可选，根据需要调整日志内容）
//            log.info("用户登录成功，userId: {}", userId);
////检查是否携带refreshToken
//// Redis 中 refresh token 的 key
//            String refreshKey = RedisConstants.REFRESH_USER_KEY + userId;
//            String refreshToken = request.getHeader("Refresh-Token");
//            //发来的请求没refreshToken那不就是假的？
//            if (refreshToken == null) {
//                log.info("refreshToken is null,无法刷新");
//                return false;
//            }
//// 判断是否存在 refresh token
//            boolean existed = stringRedisTemplate.hasKey(refreshKey);
//            String newRefreshToken = UUID.randomUUID().toString().replace("-", "");
//            // 去除连字符，更简洁
//            if (!existed) {
////                // 不存在 → 新生成并存储
////                stringRedisTemplate.opsForValue().set(refreshKey, newRefreshToken, 7, TimeUnit.DAYS);
////                log.debug("为用户 {} 创建新的 refresh token", userId);
//                log.info("redis 不存在对应的refreshToken");
//                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
//                return false;
//            } else {
//                // 已存在 → 检查是否为redis中的，-》删除旧的，重新生成新的（强制覆盖，实现单点登录）
//                String oldRefreshToken = stringRedisTemplate.opsForValue().get(refreshKey);
//                if (!refreshToken.equals(oldRefreshToken)) {
//                    log.info("refreshToken {} is old", oldRefreshToken);
//                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
//                    return false;
//                }
//                stringRedisTemplate.delete(refreshKey);
//                stringRedisTemplate.opsForValue().set(refreshKey, newRefreshToken, 7, TimeUnit.DAYS);
//                log.debug("为用户 {} 刷新 refresh token（旧 token 已失效）", userId);
//            }
//            token = JWT_UTIL.generateToken(userId, 30L, ChronoUnit.MINUTES);
//            response.setHeader("authorization", token);
//            response.setHeader("Refresh-Token", newRefreshToken);
    //// 后续可将 refreshToken 返回给客户端（例如通过 response header 或 body）
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
        Long userId;
        try {
            userId = Long.valueOf(String.valueOf(e.getClaims().get("userId")));
            log.info("从过期 token 中提取 userId: {}", userId);
        } catch (NumberFormatException ex) {
            log.warn("无法从过期 token 中解析 userId: {}", ex.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return response;
        }
        //检查是否携带refreshToken
        // Redis 中 refresh token 的 key
        String refreshKey = RedisConstants.REFRESH_USER_KEY + userId;
        String tokenKey = RedisConstants.LOGIN_USER_KEY + userId;
        String refreshToken = request.getHeader("Refresh-Token");
        String newRefreshToken = UUID.randomUUID().toString().replace("-", "");
        //发来的请求没refreshToken那不就是假的？
        token = JWT_UTIL.generateToken(userId, 30L, ChronoUnit.MINUTES);
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
        args.add(String.valueOf(TimeUnit.MINUTES.toSeconds(30)));
        //refreshToken设置为7天的过期时间
        // 判断是否存在 refresh token
        List<String> keys = new ArrayList<>();
        keys.add(refreshKey);
        keys.add(tokenKey);
        String execute = stringRedisTemplate.execute(REDIS_REFRESH_REFRESH_TOKEN_SCRIPT, keys, args.toArray());
        LuaResult luaResult = JSONUtil.toBean(execute, LuaResult.class);
        if(luaResult.getCode() == 0){
            log.info("Refresh all token lua execute failed case:{}",luaResult.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
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
