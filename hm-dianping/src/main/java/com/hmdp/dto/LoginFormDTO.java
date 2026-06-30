package com.hmdp.dto;

import lombok.Data;

/**
 * 登录表单DTO — 手机号+验证码 或 手机号+密码
 */
@Data
public class LoginFormDTO {
    private String phone;
    private String code;
    private String password;
}
