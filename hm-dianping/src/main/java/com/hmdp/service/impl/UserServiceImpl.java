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
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
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

    @Autowired
    UserMapper userMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
//    @Transactional(rollbackFor = SQLException.class)
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String code=loginForm.getCode();
        String phone=loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号不规范");
        }
        String tempCode=stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY +phone);
        if(code==null||!code.equals(tempCode)){
            log.info("传入验证码{}，实际验证码{}",loginForm.getCode(),tempCode);

            return Result.fail("验证码错误");
        }
        String token= UUID.randomUUID().toString();

        User user=new User();
        user=query().eq("phone",phone).one();

        if(user==null){
            log.info("phone={}的用户不存在",phone);
            try {
                //TODO 新用户应该还有个设置密码的功能
                user=new User().setPhone(phone);
                user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX+RandomUtil.randomString(6));
                user.setCreateTime(LocalDateTime.now());
                user.setUpdateTime(LocalDateTime.now());
                save(user);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            log.info("phone={}用户已创建",phone);
        }
        UserDTO userDTO= new UserDTO();
        BeanUtil.copyProperties(user,userDTO);
        Map<String, Object> stringObjectMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).
                        setFieldValueEditor((filedName,filedValue)->filedValue.toString()));
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY+token,stringObjectMap);
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY+token,30,TimeUnit.MINUTES);
        return Result.ok(token);
    }
    @Override
    public Result sendCode(String phone, HttpSession session) {
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号不规范");
        }
        String code= RandomUtil.randomString(6);
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY +phone,code,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.info("send code {} success",code);
        return Result.ok();
    }
}
