package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.interceptor.AutoUpdateTime;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import io.lettuce.core.BitFieldArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

    private static final List<String> TOKEN_LIST = new ArrayList<>();
    private static final List<String> PHONE_LIST = new ArrayList<>();

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
        String token = UUID.randomUUID().toString();
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
        UserDTO userDTO = new UserDTO();
        BeanUtil.copyProperties(user, userDTO);
        Map<String, Object> stringObjectMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).
                        setFieldValueEditor((filedName, filedValue) -> filedValue.toString()));
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + token, stringObjectMap);
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, 30, TimeUnit.MINUTES);
        //在这里生成刷新token
        String refreshToken = UUID.randomUUID().toString();
        stringRedisTemplate.opsForValue().set(RedisConstants.REFRESH_USER_KEY + user.getId(), refreshToken);
        stringRedisTemplate.expire(RedisConstants.REFRESH_USER_KEY+user.getId(), 2, TimeUnit.HOURS);
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        UserDTO user = UserHolder.getUser();
        LocalDateTime now = LocalDateTime.now();
        String YearMonth=now.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        String key= YearMonth+user.getId()+RedisConstants.USER_SIGN_KEY;
        int dayOfMonth=now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();
    }

    @Override
    public Result getSignCount() {
        UserDTO user = UserHolder.getUser();
        LocalDateTime now = LocalDateTime.now();
        String YearMonth=now.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        String key= YearMonth+user.getId()+RedisConstants.USER_SIGN_KEY;
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

    private String generateUserToken(String phone) {
        //先查用户是否存在

        String token = java.util.UUID.randomUUID().toString();
        User user = new User();
        user = query().eq("phone", phone).one();
        //不存在就生成用户并且插入
        if (user == null) {
            user = new User()
                    .setPhone(phone)
                    .setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(6))
                    .setCreateTime(LocalDateTime.now())
                    .setUpdateTime(LocalDateTime.now());
            save(user);
        }
        //把token塞进redis
        UserDTO userDTO = new UserDTO();
        BeanUtil.copyProperties(user, userDTO);

        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

        stringRedisTemplate.opsForHash().putAll(
                RedisConstants.LOGIN_USER_KEY + token,
                userMap);

        stringRedisTemplate.expire(
                RedisConstants.LOGIN_USER_KEY + token,
                30, TimeUnit.MINUTES);

        return token;
    }
    public void testGenerate200Tokens() {
        // 生成200个token并自动导出CSV
        generateAndExportTokens(
                200,      // 生成200个token
                true,     // 导出CSV
                "test_tokens.txt"  // 指定文件名
        );
    }
    public void generateAndExportTokens(int count, boolean exportToCsv, String csvFilePath) {
        // 清空之前的记录
        TOKEN_LIST.clear();
        PHONE_LIST.clear();

        // 生成测试token
        for (int i = 1; i <= count; i++) {
            String phone = generateTestPhone(i);
            String code = "123456"; // 统一验证码

            // 存入验证码到Redis
            stringRedisTemplate.opsForValue().set(
                    RedisConstants.LOGIN_CODE_KEY + phone,
                    code,
                    30, TimeUnit.MINUTES);

            // 生成用户token
            String token = generateUserToken(phone);
            TOKEN_LIST.add(token);
            PHONE_LIST.add(phone);

            System.out.printf("生成用户 [%d/%d] phone: %s, token: %s%n",
                    i, count, phone, token);
        }

        System.out.println("\n共生成 " + TOKEN_LIST.size() + " 个有效token");

        // 导出CSV
        if (exportToCsv) {
            String filePath = (csvFilePath == null || csvFilePath.isEmpty())
                    ? "tokens.txt" : csvFilePath;
            try {
                exportTokensToCsv(filePath);
                System.out.println("Token已导出到: " + new File(filePath).getAbsolutePath());
            } catch (IOException e) {
                System.err.println("导出CSV失败: " + e.getMessage());
            }
        }
    }

    /**
     * 导出token到CSV文件
     * @param filePath 文件路径
     */
    private void exportTokensToCsv(String filePath) throws IOException {
        try (PrintWriter writer = new PrintWriter(new File(filePath))) {
            // 写入CSV头
            writer.println("index,phone,token");

            // 写入每条记录
            for (int i = 0; i < TOKEN_LIST.size(); i++) {
                writer.printf("%d,%s,%s%n",
                        i + 1,
                        PHONE_LIST.get(i),
                        TOKEN_LIST.get(i));
            }
        }
    }
}
