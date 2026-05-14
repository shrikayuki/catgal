package com.catgal.common.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static com.catgal.common.constants.JwtClaimsConstant.*;

@Component
public class JwtUtils {

    // 固定密钥（算法自动生成符合标准）
    private static final SecretKey SECRET_KEY = Keys.secretKeyFor(io.jsonwebtoken.SignatureAlgorithm.HS256);
    private static final long EXPIRE = 604800000L; // 7天

    /**
     * 生成token（基础版，只有 userId 和 username）
     */
    public static String generateToken(Long userId, String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(USER_ID, userId);
        claims.put(USERNAME, username);

        return Jwts.builder()
                .claims(claims)
                .subject(username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRE))
                .signWith(SECRET_KEY)
                .compact();
    }

    /**
     * 生成token（完整版，包含 role）
     */
    public static String generateToken(Long userId, String username, Integer role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(USER_ID, userId);
        claims.put(USERNAME, username);
        claims.put(ROLE, role);

        return Jwts.builder()
                .claims(claims)
                .subject(username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRE))
                .signWith(SECRET_KEY)
                .compact();
    }

    /**
     * 解析token
     */
    public static Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(SECRET_KEY)
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
    public static boolean validateToken(String token) {
        Claims claims = parseToken(token);
        return claims != null && claims.getExpiration().after(new Date());
    }

    /**
     * 从token获取用户ID
     */
    public static Long getUserId(String token) {
        Claims claims = parseToken(token);
        return claims == null ? null : claims.get(USER_ID, Long.class);
    }

    /**
     * 从token获取用户名
     */
    public static String getUsername(String token) {
        Claims claims = parseToken(token);
        return claims == null ? null : claims.get(USERNAME, String.class);
    }

    /**
     * 从token获取用户角色
     */
    public static Integer getRole(String token) {
        Claims claims = parseToken(token);
        return claims == null ? null : claims.get(ROLE, Integer.class);
    }

    /**
     * 刷新token（重新计算过期时间）
     */
    public static String refreshToken(String token) {
        if (!validateToken(token)) {
            return null;
        }
        Long userId = getUserId(token);
        String username = getUsername(token);
        Integer role = getRole(token);
        return generateToken(userId, username, role);
    }
}