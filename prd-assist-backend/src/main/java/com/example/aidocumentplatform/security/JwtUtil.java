package com.example.aidocumentplatform.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

/**
 * JWT 工具类 —— 负责 Token 的生成、解析、校验。
 *
 * 使用 HMAC-SHA 算法签名，密钥与过期时间从 application.yml 中读取。
 */
@Slf4j
@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expirationMs;

    /**
     * 构造方法：从配置文件中注入 Base64 编码的密钥和过期时间。
     *
     * @param secret       Base64 编码的密钥字符串
     * @param expirationMs Token 过期时间，单位毫秒
     */
    public JwtUtil(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-ms}") long expirationMs) {
        // 将 Base64 密钥解码为 HMAC 密钥
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.expirationMs = expirationMs;
    }

    /**
     * 为用户签发 JWT Token。
     * Token 中包含用户名（subject）、签发时间、过期时间。
     *
     * @param username 登录用户名
     * @return 签发的 JWT 字符串
     */
    public String generateToken(String username) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        String token = Jwts.builder()
                .subject(username)          // 将用户名写入 subject 字段
                .issuedAt(now)              // 签发时间
                .expiration(expiry)         // 过期时间
                .signWith(key)              // 使用 HMAC-SHA 签名
                .compact();

        log.debug("JWT 签发成功: username={}, expiresAt={}", username, expiry);
        return token;
    }

    /**
     * 从 Token 中提取用户名（即 subject 字段）。
     *
     * @param token JWT 字符串
     * @return 用户名
     * @throws JwtException 如果 Token 无效或过期
     */
    public String getUsernameFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * 校验 Token 是否有效（签名正确且未过期）。
     *
     * @param token JWT 字符串
     * @return true=有效, false=无效
     */
    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("JWT 已过期: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            log.warn("不支持的 JWT 格式: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            log.warn("JWT 格式错误: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("JWT 参数非法: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 解析 Token 的 Claims（声明信息），内部方法。
     * 使用 Parser Builder 创建解析器，验证签名后提取 payload。
     */
    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)            // 设置验签密钥
                .build()                    // 构建解析器
                .parseSignedClaims(token)   // 解析已签名的 token
                .getPayload();              // 返回 claims payload
    }
}
