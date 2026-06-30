package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.LuaResult;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.constants.SystemConstants;
import com.hmdp.utils.redis.RedisConstants;
import com.hmdp.utils.redis.RedisIdWorker;
import com.hmdp.utils.security.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
    private RedisIdWorker redisIdWorker;
    @Resource
    private JwtUtil jwtUtil;
    @Resource(name = "consumeVerifyCodeScript")
    private DefaultRedisScript<String> consumeVerifyCodeScript;

    public static final DefaultRedisScript<String> REDIS_LOGIN_SET_TOKEN ;
    static {
        REDIS_LOGIN_SET_TOKEN = new DefaultRedisScript<>();
        REDIS_LOGIN_SET_TOKEN.setResultType(String.class);
        REDIS_LOGIN_SET_TOKEN.setLocation(new ClassPathResource("LoginSetToken.lua"));
    }
    @Override
//    @Transactional(rollbackFor = SQLException.class)
//    @AutoUpdateTime(printLog = true)
    public Result login(LoginFormDTO loginForm) {
        String code = loginForm.getCode();
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号不规范");
        }
        String tempCode = stringRedisTemplate.execute(consumeVerifyCodeScript, List.of(RedisConstants.LOGIN_CODE_KEY + phone));
        if (code == null || !code.equals(tempCode)) {
            log.info("传入验证码{}，实际验证码{}", loginForm.getCode(), tempCode);
            return Result.fail("验证码错误");
        }
        User user = new User();
        user = query().eq("phone", phone).one();
        if (user == null) {
            log.info("phone={}的用户不存在", phone);
            try {
                //TODO 新用户应该还有个设置密码的功能
                user = new User().setPhone(phone);
                user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(6));
                user.setCreateTime(LocalDateTime.now());
                user.setUpdateTime(LocalDateTime.now());
                save(user);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            log.info("phone={}用户已创建", phone);
        }
        Long version = redisIdWorker.nextVersion(user.getId());
        String token = jwtUtil.generateToken(user.getId(),RedisConstants.LOGIN_JWT_TTL_MINUTES, ChronoUnit.MINUTES,version);
        UserDTO userDTO = new UserDTO();
        BeanUtil.copyProperties(user, userDTO);
        //用户数据存redis查吗？
        //TODO旧token需要检查
        if (!stringRedisTemplate.hasKey(RedisConstants.LOGIN_USERINFO_MAP + userDTO.getId())) {
            Map<String, Object> stringObjectMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                    CopyOptions.create().setIgnoreNullValue(true).
                            setFieldValueEditor((filedName, filedValue) -> filedValue.toString()));
            stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USERINFO_MAP + user.getId(), stringObjectMap);
            stringRedisTemplate.expire(RedisConstants.LOGIN_USERINFO_MAP + user.getId(), 30, TimeUnit.MINUTES);
        }

        String refreshToken = UUID.randomUUID().toString().replace("-", "");
        String tokenKey = (RedisConstants.LOGIN_USER_KEY + user.getId());
        String refreshTokenKey = (RedisConstants.LOGIN_REFRESH_USER_KEY + user.getId());
        //登录请求拿最新的version
        //通过nextVersion方法可拿到最新的Version
        //在redis中设置最新Version可以让之前的token统一失效
        String versionKey =RedisConstants.LOGIN_VALID_VERSION_KEY + user.getId();
        String newVersionKey = RedisConstants.CURRENT_TOKEN_VERSION_KEY + user.getId();
        List<String> argv = new ArrayList<>();
        argv.add(token);
        argv.add(refreshToken);
        argv.add(version.toString());
        argv.add(String.valueOf(60*RedisConstants.LOGIN_JWT_TTL_MINUTES));
        argv.add(RedisConstants.LOGIN_REFRESHTOKEN_TTL_SECONDS.toString());
        //version过期时间设置与refresh过期时间相同，同步失效
        argv.add(RedisConstants.LOGIN_REFRESHTOKEN_TTL_SECONDS.toString());
        argv.add(RedisConstants.NEW_VERSION_TTL_SECONDS.toString());
        List<String> keys = new ArrayList<>();
        keys.add(tokenKey);
        keys.add(refreshTokenKey);
        keys.add(versionKey);
        keys.add(newVersionKey);
        try {
            String execute = stringRedisTemplate.execute(REDIS_LOGIN_SET_TOKEN, keys, argv.toArray());
            LuaResult luaResult = JSONUtil.toBean(execute, LuaResult.class);
            if(luaResult.getCode()!=1){
                log.info("login failed");
            }
        } catch (Exception e) {
            log.error("login failed", e);
            return Result.fail("login failed");
        }
        Map<String, String> map = new HashMap<>(3);
        map.put("token", token);
        map.put("refreshToken", refreshToken);
        log.info("【登录成功】userId={}, 返回响应体中的token(前20位)={}, refreshToken(前20位)={}, version={}",
                user.getId(),
                token.substring(0, Math.min(20, token.length())),
                refreshToken.substring(0, Math.min(20, refreshToken.length())),
                version);
        log.info("【登录成功】注意: token在响应体data中返回(data.token/data.refreshToken), 前端需从响应体而非响应头中提取");
        return Result.ok(map);
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
            return Result.fail("手机号不规范");
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

