package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.TokenPair;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.AuthService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.constants.SystemConstants;
import com.hmdp.utils.redis.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 用户服务实现 — 手机验证码登录、密码登录、签到（Redis BitMap）、双Token生成
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private AuthService authService;

    @Override
//    @Transactional(rollbackFor = SQLException.class)
//    @AutoUpdateTime(printLog = true)
    public TokenPair login(LoginFormDTO loginForm) {
        String code = loginForm.getCode();
        String phone = loginForm.getPhone();

        // ① 手机号格式校验
        if (RegexUtils.isPhoneInvalid(phone)) {
            throw new IllegalArgumentException("手机号不规范");
        }

        // ② 原子消费验证码（AuthService 负责）
        if (!authService.consumeVerifyCode(phone, code)) {
            log.info("验证码错误 phone={}, code={}", phone, code);
            throw new IllegalArgumentException("验证码错误");
        }

        // ③ 查用户 → 不存在则自动创建
        User user = query().eq("phone", phone).one();
        if (user == null) {
            user = new User().setPhone(phone)
                    .setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomNumbers(6))
                    .setCreateTime(LocalDateTime.now())
                    .setUpdateTime(LocalDateTime.now());
            save(user);
            log.info("新用户已创建 phone={}, userId={}", phone, user.getId());
        }

        // ④ 生成双 Token（AuthService 负责）
        TokenPair tokenPair = authService.generateTokenPair(user.getId());

        // ⑤ 缓存用户信息
        UserDTO userDTO = new UserDTO();
        BeanUtil.copyProperties(user, userDTO);
        authService.cacheUserInfo(userDTO);

        // ⑥ 返回 Token（暂通过响应体，Phase 1.5 后改为响应头）
        Map<String, String> map = new HashMap<>(3);
        map.put("token", tokenPair.getAccessToken());
        map.put("refreshToken", tokenPair.getRefreshToken());
        log.info("【登录成功】userId={}, token前20={}, refreshToken前20={}, version={}",
                user.getId(),
                tokenPair.getAccessToken().substring(0, Math.min(20, tokenPair.getAccessToken().length())),
                tokenPair.getRefreshToken().substring(0, Math.min(20, tokenPair.getRefreshToken().length())),
                tokenPair.getVersion());
        return tokenPair;
    }

    @Override
    public Result sign() {
        Long user = UserHolder.getUserId();
        LocalDateTime now = LocalDateTime.now();
        String YearMonth=now.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        String key= YearMonth+user+RedisConstants.USER_SIGN_KEY;
        int dayOfMonth=now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();
    }

    @Override
    public Result getSignCount() {
        Long user = UserHolder.getUserId();
        LocalDateTime now = LocalDateTime.now();
        String YearMonth=now.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        String key= YearMonth+user+RedisConstants.USER_SIGN_KEY;
        int dayOfMonth=now.getDayOfMonth();
        List<Long> bitField = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0));
        if (bitField == null || bitField.isEmpty()) {
            return Result.ok(0);
        }
        Long num = bitField.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        int count = 0;
        while (true){
            if ((num & 1) == 0) {
                //代表未签到
                break;
            }
            else {
                count++;
            }
            //无符号右移
            num>>>=1;
        }
        return Result.ok(count);
    }

    @Override
    public Result sendCode(String phone) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            throw new IllegalArgumentException("手机号不规范");
        }
        String freqKey = "login:code:freq:" + phone;
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(freqKey))) {
            return Result.fail("发送太频繁，请稍后再试");
        }
        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        stringRedisTemplate.opsForValue().set(freqKey, "1", 60, TimeUnit.SECONDS);
        log.info("send code {} success", code);
        return Result.ok();
    }
}

