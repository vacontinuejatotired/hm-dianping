package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
/**
 * 用户服务接口 — 登录/注册、验证码、签到、Token生成
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone);
    Result login(LoginFormDTO loginForm);

    Result sign();

    Result getSignCount();
}
