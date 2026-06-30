package com.hmdp.controller;


import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.TokenPair;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.cache.CacheClient;
import com.hmdp.utils.redis.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 用户控制器 — 注册、登录、用户信息查询、签到
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;
    @Resource
    private CacheClient cacheClient;

    /**
     * 发送手机验证码
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone) {
        return userService.sendCode(phone);
    }

    /**
     * 登录功能 — Token 通过响应头返回（authorization + Refresh-Token）
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     * @param response 用于写回 Token 响应头
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpServletResponse response){
        TokenPair tokenPair = userService.login(loginForm);
        response.setHeader("authorization", tokenPair.getAccessToken());
        response.setHeader("Refresh-Token", tokenPair.getRefreshToken());
        return Result.ok();
    }

    /**
     * 登出功能
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout(){
        // TODO 实现登出功能
        return Result.fail("功能未完成");
    }

    @GetMapping("/me")
    public Result me(){
        log.info("{}",UserHolder.getUserDTO());
        return Result.ok(UserHolder.getUserDTO());
    }

    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id") Long userId){
        // 权限校验：只能查自己的信息
        Long currentUserId = UserHolder.getUserId();
        if (!currentUserId.equals(userId)) {
            log.warn("越权访问: currentUserId={}, targetUserId={}", currentUserId, userId);
            return Result.fail("无权访问该用户信息");
        }
        // 缓存查询（缓存穿透防护）
        User user = cacheClient.queryById(userId, User.class, "cache:user:",
                id -> userService.getById(id), 30L, TimeUnit.MINUTES);
        if (user == null) {
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        return Result.ok(userDTO);
    }
    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId){
        // 权限校验：只能查自己的详细信息
        Long currentUserId = UserHolder.getUserId();
        if (!currentUserId.equals(userId)) {
            log.warn("越权访问详情: currentUserId={}, targetUserId={}", currentUserId, userId);
            return Result.fail("无权访问该用户详细信息");
        }
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }
    @PostMapping("/sign")
    public Result sign(){
        return userService.sign();
    }
    @GetMapping("/sign/count")
    public Result signCount(){
        return userService.getSignCount();
    }
}
