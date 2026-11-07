package com.catgal.common.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static com.catgal.common.constants.JwtClaimsConstant.*;

@Component
public class JwtUtils {

    private final SecretKey secretKey;
    private final long expire;

    public JwtUtils(@Value("${security.jwt.secret}") String base64Secret,
                    @Value("${security.jwt.expire-ms:604800000}") long expire) {
        if (base64Secret == null || base64Secret.isBlank()) {
            throw new IllegalArgumentException("JWT secret must not be blank");
        }
        this.secretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret));
        this.expire = expire;
    }

    /**
     * 生成token（基础版，只有 userId 和 username）
     */
    public String generateToken(Long userId, String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(USER_ID, userId);
        claims.put(USERNAME, username);

        return Jwts.builder()
                .claims(claims)
                .subject(username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expire))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 生成token（完整版，包含 role）
     */
    public String generateToken(Long userId, String username, Integer role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(USER_ID, userId);
        claims.put(USERNAME, username);
        claims.put(ROLE, role);

        return Jwts.builder()
                .claims(claims)
                .subject(username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expire))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 解析token
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 验证token是否有效
     */
    public boolean validateToken(String token) {
        Claims claims = parseToken(token);
        return claims != null && claims.getExpiration().after(new Date());
    }

    /**
     * 从token获取用户ID
     */
    public Long getUserId(String token) {
        Claims claims = parseToken(token);
        return claims == null ? null : claims.get(USER_ID, Long.class);
    }

    /**
     * 从token获取用户名
     */
    public String getUsername(String token) {
        Claims claims = parseToken(token);
        return claims == null ? null : claims.get(USERNAME, String.class);
    }

    /**
     * 从token获取用户角色
     */
    public Integer getRole(String token) {
        Claims claims = parseToken(token);
        return claims == null ? null : claims.get(ROLE, Integer.class);
    }

    /**
     * 刷新token（重新计算过期时间）
     */
    public String refreshToken(String token) {
        if (!validateToken(token)) {
            return null;
        }
        Long userId = getUserId(token);
        String username = getUsername(token);
        Integer role = getRole(token);
        return generateToken(userId, username, role);
    }
}
