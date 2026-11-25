/*
 * 阅读说明
 * 密码 BCrypt 编码与校验入口，注册和登录复用同一算法。
 */
package com.catgal.common.utils;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class PasswordUtils {
    
    private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    
    public static String encode(String raw) {
        return encoder.encode(raw);
    }
    
    public static boolean matches(String raw, String encoded) {
        return encoder.matches(raw, encoded);
    }
}