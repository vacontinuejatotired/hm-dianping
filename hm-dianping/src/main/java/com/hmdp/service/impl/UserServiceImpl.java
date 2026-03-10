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
import com.hmdp.utils.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
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
    private static final List<String> TOKEN_LIST = new ArrayList<>();
    private static final List<String> PHONE_LIST = new ArrayList<>();
    private static final List<String> REFRESHTOKEN_LIST = new ArrayList<>();

    public static final DefaultRedisScript<String> REDIS_LOGIN_SET_TOKEN ;
    static {
        REDIS_LOGIN_SET_TOKEN = new DefaultRedisScript<>();
        REDIS_LOGIN_SET_TOKEN.setResultType(String.class);
        REDIS_LOGIN_SET_TOKEN.setLocation(new ClassPathResource("LoginSetToken.lua"));
    }
    @Override
//    @Transactional(rollbackFor = SQLException.class)
//    @AutoUpdateTime(printLog = true)
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String code = loginForm.getCode();
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号不规范");
        }
        String tempCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
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
        log.info("map={}", map);
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
    public Result sendCode(String phone, HttpSession session) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号不规范");
        }
        String code = RandomUtil.randomString(6);
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.info("send code {} success", code);
        return Result.ok();
    }

    // 获取生成的token列表
    public static List<String> getTokenList() {
        return new ArrayList<>(TOKEN_LIST);
    }

    // 获取生成的手机号列表
    public static List<String> getPhoneList() {
        return new ArrayList<>(PHONE_LIST);
    }

    private String generateTestPhone(int index) {
        return String.format("137%08d", index);
    }

    /**
     * 用于测试的批量生成两种token方法
     * @param size
     */
    private void generateTestTokenAndRefreshToken(int size) {
        TOKEN_LIST.clear();
        REFRESHTOKEN_LIST.clear();
        try {
            // 参数校验
            if (size <= 0) {
                log.error("generateTestTokenAndRefreshToken failed: size must be positive, current size: {}", size);
                return;
            }

            if (PHONE_LIST.isEmpty()) {
                log.error("generateTestTokenAndRefreshToken failed: PHONE_LIST is empty");
                return;
            }

            // 查询已存在的用户
            List<User> existingUsers;
            try {
                existingUsers = query().in("phone", PHONE_LIST).list();
                log.info("Query users success, found {} users", existingUsers.size());
            } catch (Exception e) {
                log.error("Query users failed: {}", e.getMessage(), e);
                return;
            }

            // 找出缺失的手机号并创建新用户
            Set<String> existingPhones = existingUsers.stream()
                    .map(User::getPhone)
                    .collect(Collectors.toSet());
            List<User> newUsers = new ArrayList<>();
            List<String> missingPhones = new ArrayList<>();
            for (String phone : PHONE_LIST) {
                if (!existingPhones.contains(phone)) {
                    missingPhones.add(phone);
                    // 创建新用户
                    User newUser = new User()
                            .setPhone(phone)
                            .setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(6))
                            .setCreateTime(LocalDateTime.now())
                            .setUpdateTime(LocalDateTime.now());
                    newUsers.add(newUser);
                }
            }

            // 批量插入新用户
            if (!newUsers.isEmpty()) {
                log.info("Creating {} new users for missing phones", newUsers.size());
                try {
                    saveBatch(newUsers);
                    log.info("Successfully created {} new users", newUsers.size());

                    // 重新查询所有用户（包括刚创建的）
                    existingUsers = query().in("phone", PHONE_LIST).list();
                    log.info("Re-query users success, now found {} users", existingUsers.size());
                } catch (Exception e) {
                    log.error("Failed to create new users: {}", e.getMessage(), e);
                    return;
                }
            }

            int count = 0;
            // 用于存储每个用户生成的version
            Map<Long, Long> userVersionMap = new HashMap<>();

            for (User user : existingUsers) {
                try {
                    // 生成token
                    Long version = redisIdWorker.nextVersion(user.getId());
                    String token = jwtUtil.generateToken(user.getId(), 1800L, ChronoUnit.SECONDS, version);
                    if (token == null || token.isEmpty()) {
                        log.error("Generate token failed for user: {}", user.getId());
                        continue;
                    }
                    TOKEN_LIST.add(token);

                    // 生成refresh token
                    String refreshToken = UUID.randomUUID().toString().replace("-", "");
                    REFRESHTOKEN_LIST.add(refreshToken);

                    // 记录version
                    userVersionMap.put(user.getId(), version);

                    count++;
                    log.debug("generateTestTokenAndRefreshToken {} success, have generate {}, version: {}",
                            user.getId(), count, version);

                } catch (Exception e) {
                    log.error("Generate token for user {} failed: {}", user.getId(), e.getMessage(), e);
                }
            }

            // 批量插入Redis - 使用简单的set
            if (!TOKEN_LIST.isEmpty() && TOKEN_LIST.size() == REFRESHTOKEN_LIST.size()) {
                try {
                    // 使用pipeline批量操作
                    List<User> finalExistingUsers = existingUsers;
                    stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                        for (int i = 0; i < TOKEN_LIST.size(); i++) {
                            String token = TOKEN_LIST.get(i);
                            String refreshToken = REFRESHTOKEN_LIST.get(i);
                            User user = finalExistingUsers.get(i);
                            Long version = userVersionMap.get(user.getId());

                            // 1. 存token
                            String tokenKey = RedisConstants.LOGIN_USER_KEY + user.getId();
                            connection.set(tokenKey.getBytes(StandardCharsets.UTF_8),
                                    token.getBytes(StandardCharsets.UTF_8));
                            connection.expire(tokenKey.getBytes(StandardCharsets.UTF_8),
                                     1800L); // 30分钟

                            // 2. 存refreshToken
                            String refreshKey = RedisConstants.LOGIN_REFRESH_USER_KEY + user.getId();
                            connection.set(refreshKey.getBytes(StandardCharsets.UTF_8),
                                    refreshToken.getBytes(StandardCharsets.UTF_8));
                            connection.expire(refreshKey.getBytes(StandardCharsets.UTF_8),
                                    RedisConstants.LOGIN_REFRESHTOKEN_TTL_SECONDS); // 7天

                            // 3. 存有效version（使用刚生成的version）
                            String validVersionKey = RedisConstants.LOGIN_VALID_VERSION_KEY + user.getId();
                            connection.set(validVersionKey.getBytes(StandardCharsets.UTF_8),
                                    version.toString().getBytes(StandardCharsets.UTF_8));
                            connection.expire(validVersionKey.getBytes(StandardCharsets.UTF_8),
                                    RedisConstants.LOGIN_REFRESHTOKEN_TTL_SECONDS); // 7天

                            // 4. 存最新version计数器（使用刚生成的version）
                            String newVersionKey = RedisConstants.CURRENT_TOKEN_VERSION_KEY + user.getId();
                            connection.set(newVersionKey.getBytes(StandardCharsets.UTF_8),
                                    version.toString().getBytes(StandardCharsets.UTF_8));
                            connection.expire(newVersionKey.getBytes(StandardCharsets.UTF_8),
                                    8 * 24 * 60 * 60); // 8天
                        }
                        return null;
                    });

                    log.info("Successfully inserted {} tokens into Redis using pipeline", TOKEN_LIST.size());

                } catch (Exception e) {
                    log.error("Failed to batch insert tokens to Redis: {}", e.getMessage(), e);
                }
            }

            // 结果校验
            if (size != count) {
                log.warn("generateTestTokenAndRefreshToken: expected {}, actually generated {} (missing: {})",
                        size, count, size - count);
            } else {
                log.info("generate {} tokens success, all tokens generated and inserted to Redis", size);
            }

            // 检查列表长度是否一致
            if (TOKEN_LIST.size() != REFRESHTOKEN_LIST.size()) {
                log.error("Data inconsistency: token size {} != refreshToken size {}",
                        TOKEN_LIST.size(), REFRESHTOKEN_LIST.size());
            }

        } catch (Exception e) {
            log.error("generateTestTokenAndRefreshToken unexpected error: {}", e.getMessage(), e);
        }
    }
    /**
     * 两种token导入到指定文件中
     */
    public void exportTokenAndRefreshTokenToCsv(int size, String fileName) {
        for (int i = 0; i < size; i++) {
            PHONE_LIST.add(generateTestPhone(i));
        }
        log.info("generate {} phone, admin really confirm {}", PHONE_LIST.size(), size);
        if (size != PHONE_LIST.size()) {
            log.info(" num size is not match");
        }
        fileName = size + fileName;
        generateTestTokenAndRefreshToken(size);
        // 写入CSV文件
        String filePath = "tokens_" + fileName + ".csv";
        try (FileWriter writer = new FileWriter(filePath);
             PrintWriter pw = new PrintWriter(writer)) {
            // 写入CSV头
            pw.println("token,refreshToken");
            // 写入数据
            for (int i = 0; i < PHONE_LIST.size(); i++) {
                String token = TOKEN_LIST.get(i);
                String refreshToken = REFRESHTOKEN_LIST.get(i);

                pw.printf("%s,%s%n", token, refreshToken);
            }
            log.info("CSV file exported: {}", filePath);
        } catch (IOException e) {
            log.error("Failed to write CSV file", e);
        }
    }
}
