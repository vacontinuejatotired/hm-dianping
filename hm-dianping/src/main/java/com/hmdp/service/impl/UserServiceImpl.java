package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.PasswordChangeDTO;
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
import com.hmdp.utils.security.PasswordEncoder;
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
        String password = loginForm.getPassword();
        String phone = loginForm.getPhone();

        // ① 手机号格式校验
        if (RegexUtils.isPhoneInvalid(phone)) {
            throw new IllegalArgumentException("手机号不规范");
        }

        // ② 密码登录 / 验证码登录 二选一
        if (password != null) {
            return loginByPassword(phone, password);
        }
        return loginByCode(phone, loginForm.getCode());
    }

    /**
     * 密码登录
     */
    private TokenPair loginByPassword(String phone, String password) {
        // 查用户
        User user = query().eq("phone", phone).one();
        if (user == null || user.getPassword() == null) {
            throw new IllegalArgumentException("账号或密码错误");
        }

        // 账户锁定检查
        String lockKey = RedisConstants.LOGIN_LOCK_KEY + phone;
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(lockKey))) {
            throw new IllegalArgumentException("账户已锁定，请 30 分钟后重试");
        }

        // 校验密码
        if (!PasswordEncoder.matches(password, user.getPassword())) {
            // 失败计数累加
            String failKey = RedisConstants.LOGIN_FAIL_COUNT_KEY + phone;
            Long fails = stringRedisTemplate.opsForValue().increment(failKey);
            if (fails == 1) {
                stringRedisTemplate.expire(failKey, RedisConstants.LOGIN_FAIL_COUNT_TTL, TimeUnit.SECONDS);
            }
            if (fails >= RedisConstants.LOGIN_FAIL_COUNT_LOCK) {
                stringRedisTemplate.opsForValue().set(lockKey, "1",
                        RedisConstants.LOGIN_LOCK_TTL, TimeUnit.SECONDS);
                log.warn("账户连续登录失败被锁定 phone={}", phone);
                stringRedisTemplate.delete(failKey);
                throw new IllegalArgumentException("账户已锁定，请 30 分钟后重试");
            }
            log.info("密码错误 phone={}, 失败次数={}", phone, fails);
            throw new IllegalArgumentException("账号或密码错误");
        }

        // 登录成功：清除失败计数
        stringRedisTemplate.delete(RedisConstants.LOGIN_FAIL_COUNT_KEY + phone);

        // 旧 MD5 格式密码 → 自动升级为 bcrypt
        if (!user.getPassword().startsWith("$2")) {
            user.setPassword(PasswordEncoder.encode(password));
            user.setUpdateTime(LocalDateTime.now());
            updateById(user);
            log.info("密码自动升级为 bcrypt userId={}", user.getId());
        }

        // 生成 Token
        TokenPair tokenPair = authService.generateTokenPair(user.getId());
        UserDTO userDTO = new UserDTO();
        BeanUtil.copyProperties(user, userDTO);

        log.info("【密码登录成功】userId={}", user.getId());
        return tokenPair;
    }

    /**
     * 验证码登录（原有逻辑）
     */
    private TokenPair loginByCode(String phone, String code) {
        // 原子消费验证码
        if (!authService.consumeVerifyCode(phone, code)) {
            log.info("验证码错误 phone={}, code={}", phone, code);
            throw new IllegalArgumentException("验证码错误");
        }

        // 查用户 → 不存在则自动创建
        User user = query().eq("phone", phone).one();
        if (user == null) {
            user = new User().setPhone(phone)
                    .setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomNumbers(6))
                    .setCreateTime(LocalDateTime.now())
                    .setUpdateTime(LocalDateTime.now());
            save(user);
            log.info("新用户已创建 phone={}, userId={}", phone, user.getId());
        }

        // 生成 Token
        TokenPair tokenPair = authService.generateTokenPair(user.getId());
        UserDTO userDTO = new UserDTO();
        BeanUtil.copyProperties(user, userDTO);

        log.info("【验证码登录成功】userId={}", user.getId());
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
    public void logout(Long userId) {
        authService.revokeTokens(userId);
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

    @Override
    public TokenPair changePassword(PasswordChangeDTO dto) {
        Long userId = UserHolder.getUserId();
        if (userId == null) {
            throw new IllegalArgumentException("未登录");
        }

        String oldPassword = dto.getOldPassword();
        String newPassword = dto.getNewPassword();

        if (oldPassword == null || newPassword == null) {
            throw new IllegalArgumentException("旧密码和新密码不能为空");
        }

        // 新密码强度校验
        if (RegexUtils.isPasswordInvalid(newPassword)) {
            throw new IllegalArgumentException("密码需至少8位，包含大写、小写、数字");
        }

        // 查用户
        User user = getById(userId);
        if (user == null || user.getPassword() == null) {
            throw new IllegalArgumentException("未设置密码，请使用验证码登录");
        }

        // 校验旧密码
        if (!PasswordEncoder.matches(oldPassword, user.getPassword())) {
            log.warn("修改密码失败：旧密码错误 userId={}", userId);
            throw new IllegalArgumentException("旧密码错误");
        }

        // 更新密码
        user.setPassword(PasswordEncoder.encode(newPassword));
        user.setUpdateTime(LocalDateTime.now());
        updateById(user);

        // 生成全新双 Token（bump version → 旧 Token 自动失效）
        TokenPair tokenPair = authService.generateTokenPair(userId);
        log.info("【密码修改成功】userId={}, 已bump version", userId);
        return tokenPair;
    }
}

