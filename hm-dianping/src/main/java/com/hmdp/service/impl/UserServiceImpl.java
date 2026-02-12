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
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.*;
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
import java.util.*;
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

    @Resource
    private JwtUtil jwtUtil;
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
        String token = jwtUtil.generateToken(user.getId());
        UserDTO userDTO = new UserDTO();
        BeanUtil.copyProperties(user, userDTO);
        //用户数据存redis查吗？
        //TODO旧token需要检查
        Map<String, Object> stringObjectMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).
                        setFieldValueEditor((filedName, filedValue) -> filedValue.toString()));
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + token, stringObjectMap);
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, 30, TimeUnit.MINUTES);
        //在这里生成刷新token
        String refreshKey = RedisConstants.REFRESH_USER_KEY + user.getId();
        String refreshToken;
        if (stringRedisTemplate.hasKey(refreshKey)) {
            stringRedisTemplate.delete(refreshKey);
        }
        refreshToken = UUID.randomUUID().toString().replace("-", "");
        stringRedisTemplate.opsForValue().set(refreshKey, refreshToken, 7, TimeUnit.DAYS);
        Map<String, String> map = new HashMap<>();
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
    public void testGenerateTokens(int num,String filename) {
        generateAndExportTokens(
                num,      // 生成200个token
                true,     // 导出CSV
                filename  // 指定文件名
        );
    }
    public void generateAndExportTokens(int count, boolean exportToCsv, String csvFilePath) {
        System.out.println("============= 开始生成Token =============");
        System.out.println("目标生成数量: " + count);
        System.out.println("导出CSV: " + exportToCsv);

        // 清空之前的记录
        TOKEN_LIST.clear();
        PHONE_LIST.clear();
        System.out.println("已清空之前的记录");

        // 统计变量
        int successCount = 0;
        int redisSuccessCount = 0;
        int tokenSuccessCount = 0;
        int failedCount = 0;
        List<String> failedPhones = new ArrayList<>();

        // 生成测试token
        for (int i = 1; i <= count; i++) {
            String phone = null;
            String token = null;

            try {
                phone = generateTestPhone(i);
                System.out.printf("\n[%d/%d] 处理手机号: %s%n", i, count, phone);

                String code = "123456"; // 统一验证码

                // 存入验证码到Redis
                try {
                    stringRedisTemplate.opsForValue().set(
                            RedisConstants.LOGIN_CODE_KEY + phone,
                            code,
                            300, TimeUnit.MINUTES);
                    redisSuccessCount++;
                    System.out.println("  ✓ Redis验证码设置成功");
                } catch (Exception e) {
                    System.err.println("  ✗ Redis验证码设置失败: " + e.getMessage());
                    // 继续尝试生成token，不立即失败
                }

                // 生成用户token
                try {
                    token = generateUserToken(phone,300);
                    if (token == null || token.trim().isEmpty()) {
                        throw new RuntimeException("生成的token为空");
                    }

                    TOKEN_LIST.add(token);
                    PHONE_LIST.add(phone);
                    tokenSuccessCount++;
                    successCount++;

                    System.out.println("  ✓ Token生成成功: " + token.substring(0, Math.min(20, token.length())) + "...");

                } catch (Exception e) {
                    failedCount++;
                    failedPhones.add(phone);
                    System.err.println("  ✗ Token生成失败: " + e.getMessage());
                    // 继续处理下一个，不中断循环
                }

            } catch (Exception e) {
                // 外层异常处理
                failedCount++;
                String failedPhone = (phone != null) ? phone : "未知手机号";
                failedPhones.add(failedPhone);
                System.err.printf("  ✗ 处理第 %d 个时发生外层异常: %s%n", i, e.getMessage());
            }

            // 每50个输出一次进度
            if (i % 50 == 0) {
                System.out.printf("\n[进度报告] 已处理 %d/%d, 成功: %d, 失败: %d%n",
                        i, count, successCount, failedCount);
            }
        }

        System.out.println("\n============= 生成完成 =============");
        System.out.println("目标数量: " + count);
        System.out.println("成功生成: " + successCount);
        System.out.println("失败数量: " + failedCount);
        System.out.println("Redis操作成功: " + redisSuccessCount);
        System.out.println("Token生成成功: " + tokenSuccessCount);
        System.out.println("TOKEN_LIST大小: " + TOKEN_LIST.size());
        System.out.println("PHONE_LIST大小: " + PHONE_LIST.size());

        if (!failedPhones.isEmpty()) {
            System.out.println("\n失败手机号列表:");
            for (int i = 0; i < Math.min(10, failedPhones.size()); i++) {
                System.out.println("  " + failedPhones.get(i));
            }
            if (failedPhones.size() > 10) {
                System.out.println("  ... 还有 " + (failedPhones.size() - 10) + " 个失败记录");
            }
        }

        // 检查是否有重复
        try {
            Set<String> uniquePhones = new HashSet<>(PHONE_LIST);
            if (uniquePhones.size() != PHONE_LIST.size()) {
                System.out.println("\n⚠️ 警告: 发现重复手机号!");
                System.out.println("  列表数量: " + PHONE_LIST.size());
                System.out.println("  唯一数量: " + uniquePhones.size());
                System.out.println("  重复数量: " + (PHONE_LIST.size() - uniquePhones.size()));
            }

            Set<String> uniqueTokens = new HashSet<>(TOKEN_LIST);
            if (uniqueTokens.size() != TOKEN_LIST.size()) {
                System.out.println("\n⚠️ 警告: 发现重复Token!");
                System.out.println("  列表数量: " + TOKEN_LIST.size());
                System.out.println("  唯一数量: " + uniqueTokens.size());
            }
        } catch (Exception e) {
            System.err.println("检查重复时出错: " + e.getMessage());
        }

        // 导出CSV
        if (exportToCsv) {
            String filePath = (csvFilePath == null || csvFilePath.isEmpty())
                    ? "tokens_" + System.currentTimeMillis() + ".txt" : csvFilePath;

            System.out.println("\n尝试导出到CSV文件: " + filePath);

            try {
                exportTokensToCsv(filePath);
                File file = new File(filePath);
                System.out.println("✓ Token已成功导出到: " + file.getAbsolutePath());
                System.out.println("  文件大小: " + file.length() + " 字节");

                // 验证文件内容
                if (file.exists() && file.length() > 0) {
                    System.out.println("  导出验证: 成功");
                } else {
                    System.out.println("  ⚠️ 导出验证: 文件可能为空或创建失败");
                }

            } catch (IOException e) {
                System.err.println("✗ 导出CSV失败: " + e.getMessage());
                e.printStackTrace();
            } catch (Exception e) {
                System.err.println("✗ 导出过程中发生意外错误: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.out.println("\n跳过CSV导出");
        }

        System.out.println("\n============= 任务结束 =============");
    }

    private String generateUserToken(String phone,int expireTime) {
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
                expireTime, TimeUnit.MINUTES);

        return token;
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
                log.info("导入{}条",i+1);
            }
        }
    }
}
