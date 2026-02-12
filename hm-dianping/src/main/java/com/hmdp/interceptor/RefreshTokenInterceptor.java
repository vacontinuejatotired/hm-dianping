package com.hmdp.interceptor;

import cn.hutool.core.lang.UUID;
import com.hmdp.utils.JwtUtil;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private static final JwtUtil JWT_UTIL = new JwtUtil(new DefaultResourceLoader());
    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        log.info("拦截路径 {}", request.getRequestURI());
        String uri = request.getRequestURI();
        if (uri.equals("/blog/hot") || uri.startsWith("/blog/hot")
                || uri.equals("/user/login")
                || uri.equals("/user/code")
                || uri.startsWith("/shop-type")
                || uri.startsWith("/shop")) {
            log.info("公开路径，直接放行: {}", uri);
            return true;
        }

        String token = request.getHeader("authorization");
        //?没token返回成功干嘛？
        if (token == null) {
            log.info("token is null");
            return false;
        }
        //Map userMap=stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY+token);
        Map userMap = null;
        try {
            userMap = JWT_UTIL.ValiateAndGetClaimFromToken(token);
            if (userMap == null || userMap.isEmpty()) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return false;
            }
            Long userId = Long.valueOf(String.valueOf(userMap.get("userId"))) ;
            UserHolder.saveUserId(userId);
            stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, 30, TimeUnit.MINUTES);
            //处理jwt过期逻辑
        } catch (ExpiredJwtException e) {
            // 假设 userMap 来自登录验证结果
            Long userId = (Long) e.getClaims().get("userId");
// 记录登录事件（可选，根据需要调整日志内容）
            log.info("用户登录成功，userId: {}", userId);
//检查是否携带refreshToken
// Redis 中 refresh token 的 key
            String refreshKey = RedisConstants.REFRESH_USER_KEY+userId;
            String refreshToken = request.getHeader("Refresh-Token");
            //发来的请求没refreshToken那不就是假的？
            if (refreshToken == null) {
                log.info("refreshToken is null,无法刷新");
                return false;
            }
// 判断是否存在 refresh token
            boolean existed = stringRedisTemplate.hasKey(refreshKey);
            String newRefreshToken = UUID.randomUUID().toString().replace("-", "");
             // 去除连字符，更简洁
            if (!existed) {
//                // 不存在 → 新生成并存储
//                stringRedisTemplate.opsForValue().set(refreshKey, newRefreshToken, 7, TimeUnit.DAYS);
//                log.debug("为用户 {} 创建新的 refresh token", userId);
                log.info("redis 不存在对应的refreshToken");
                return false;
            } else {
                // 已存在 → 检查是否为redis中的，-》删除旧的，重新生成新的（强制覆盖，实现单点登录）
                String oldRefreshToken = stringRedisTemplate.opsForValue().get(refreshKey);
                if (!refreshToken.equals(oldRefreshToken)) {
                    log.info("refreshToken {} is old",oldRefreshToken);
                    return false;
                }
                stringRedisTemplate.delete(refreshKey);
                stringRedisTemplate.opsForValue().set(refreshKey, newRefreshToken, 7, TimeUnit.DAYS);
                log.debug("为用户 {} 刷新 refresh token（旧 token 已失效）", userId);
            }
            token = JWT_UTIL.generateToken(userId);
            response.setHeader("authorization", token);
            response.setHeader("Refresh-Token", newRefreshToken);
// 后续可将 refreshToken 返回给客户端（例如通过 response header 或 body）
        }
        catch (Exception e) {
            log.error(e.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
