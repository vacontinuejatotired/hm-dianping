package com.hmdp.utils;

import io.jsonwebtoken.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.TemporalUnit;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
@Slf4j
public class JwtUtil {

    private static PrivateKey PRIVATE_KEY;
    private static PublicKey PUBLIC_KEY;

    private final ResourceLoader resourceLoader;

    public JwtUtil(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    public void init() {
        PRIVATE_KEY= loadRsaPrivateKey();
        PUBLIC_KEY= loadRsaPublicKey();
    }

    private PublicKey loadRsaPublicKey() {
        try {
            // 从 classpath 读取 public.pem（路径：src/main/resources/public.pem）
            Resource resource = new ClassPathResource("public.pem");
            if (!resource.exists()) {
                throw new IllegalStateException("public.pem not found in classpath");
            }

            // 读取文件内容为字符串
            String pem = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            // 清理 PEM 格式
            String publicKeyContent = pem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");  // 移除所有换行、空格等
            System.out.println(publicKeyContent);
            byte[] keyBytes = Base64.getDecoder().decode(publicKeyContent);

            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePublic(spec);

        } catch (Exception e) {
            throw new RuntimeException("Failed to load RSA public key from classpath", e);
        }
    }

    private PrivateKey loadRsaPrivateKey() {
        try {
            Resource resource = new ClassPathResource("private-pkcs8.pem");
            if (!resource.exists()) {
                throw new IllegalStateException("private-pkcs8.pem not found in classpath");
            }

            String pem = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            // 针对您的实际头尾格式（无空格） + 兼容标准格式
            String privateKeyContent = pem
                    // 匹配无空格的变体（最常见问题）
                    .replace("-----BEGINRSAPRIVATEKEY-----", "")
                    .replace("-----ENDRSAPRIVATEKEY-----", "")
                    // 兼容标准格式（有空格）
                    .replaceAll("-----BEGIN\\s+RSA\\s+PRIVATE\\s+KEY-----", "")
                    .replaceAll("-----END\\s+RSA\\s+PRIVATE\\s+KEY-----", "")
                    .replaceAll("-----BEGIN\\s+PRIVATE\\s+KEY-----", "")
                    .replaceAll("-----END\\s+PRIVATE\\s+KEY-----", "")
                    // 最终移除所有剩余空白字符（换行、空格、制表符）
                    .replaceAll("\\s+", "");

            // 调试输出：确认清理后是否为纯 Base64
            System.out.println("清理后的 Base64 字符串长度: " + privateKeyContent.length());
            System.out.println("清理后的 Base64 前 50 字符: " + privateKeyContent.substring(0, Math.min(50, privateKeyContent.length())));

            byte[] keyBytes = Base64.getDecoder().decode(privateKeyContent);

            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePrivate(spec);

        } catch (Exception e) {
            throw new RuntimeException("Failed to load RSA private key from classpath", e);
        }
    }
    public String generateToken(Long userId,Long timeExpired,TemporalUnit temporalUnit) {
        return generateToken(userId,timeExpired,temporalUnit,null);
    }

    public String generateToken(Long  userId, Long timeExpired, TemporalUnit temporalUnit,Long version) {
        Map<String, Object> claims = new HashMap<>(4);
        claims.put("userId", userId);
        if(version != null) {
            claims.put("version", version);
        }
        // 使用当前时间 + 30 分钟作为过期时间（Unix 时间戳，秒级）
        Instant now = Instant.now();
        Instant exp = now.plus(timeExpired, temporalUnit);
        return Jwts.builder()
                .claims(claims)
                .expiration(Date.from(exp))
                .issuedAt(Date.from(now))
                .signWith(SignatureAlgorithm.RS256, PRIVATE_KEY)
                // RS256 = SHA256withRSA
                .compact();
    }

    public Claims valiateAndGetClaimFromToken(String token) throws JwtException {

        if (PUBLIC_KEY == null) {
            log.error("Public key is null - cannot verify JWT signature");
            throw new IllegalStateException("Public key not initialized");
        }

        if (token == null || token.trim().isEmpty()) {
            log.warn("Token is null or empty");
            throw new IllegalArgumentException("Token cannot be null or empty");
        }

        try {
            return Jwts.parser()
                    .verifyWith(PUBLIC_KEY)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

        } catch (ExpiredJwtException e) {
            log.info("Token has expired, userId from claims: {}", e.getClaims().get("userId"));
            throw e;  // 必须抛出，让拦截器捕获并处理刷新逻辑

        } catch (PrematureJwtException e) {
            log.warn("Token used before not-before time: {}", e.getMessage());
            throw e;

        } catch (UnsupportedJwtException e) {
            log.warn("Unsupported JWT format: {}", e.getMessage());
            throw new JwtException("Unsupported JWT format", e);

        } catch (MalformedJwtException e) {
            log.warn("Malformed JWT: {}", e.getMessage());
            throw new JwtException("Malformed JWT", e);

        } catch (SignatureException e) {
            log.warn("Invalid JWT signature: {}", e.getMessage());
            throw new JwtException("Invalid JWT signature", e);

        } catch (JwtException e) {
            log.warn("JWT validation failed: {}", e.getMessage(), e);
            throw e;  // 其他 JWT 异常直接抛出

        } catch (Exception e) {
            log.error("Unexpected error during JWT validation", e);
            throw new JwtException("Unexpected JWT validation error", e);
        }
    }
}
