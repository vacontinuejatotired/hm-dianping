package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Token 对 — 登录/刷新时返回的完整凭证，由 AuthService 生成，不含 HTTP 细节
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TokenPair {
    private String accessToken;
    private String refreshToken;
    private Long version;
}
