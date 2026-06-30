package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import jakarta.servlet.http.HttpSession;

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

    Result sendCode(String phone, HttpSession session);
    Result login(LoginFormDTO loginForm, HttpSession session);

    Result sign();

    Result getSignCount();

    /**
     * 指定生成多少个可用的token并且导入致指定csv文件
     * @param size
     */
    void exportTokenAndRefreshTokenToCsv(int size, String fileName);
}
