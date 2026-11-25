/*
 * 阅读说明
 * 当前 Java 请求的线程内身份上下文。
 * 拦截器认证后写入，完成请求后清理，异步线程需要显式传递所需身份。
 */
package com.catgal.common.context;

import lombok.Data;

/**
 * 用户上下文（存储当前登录用户信息）
 */
public class UserContext {

    private static final ThreadLocal<UserInfo> CONTEXT = new ThreadLocal<>();

    /**
     * 设置当前用户信息
     */
    public static void setUser(UserInfo userInfo) {
        CONTEXT.set(userInfo);
    }

    /**
     * 获取当前用户信息
     */
    public static UserInfo getUser() {
        return CONTEXT.get();
    }

    /**
     * 获取当前用户ID
     */
    public static Long getUserId() {
        UserInfo userInfo = CONTEXT.get();
        return userInfo == null ? null : userInfo.getUserId();
    }

    /**
     * 获取当前用户名
     */
    public static String getUsername() {
        UserInfo userInfo = CONTEXT.get();
        return userInfo == null ? null : userInfo.getUsername();
    }

    /**
     * 获取当前用户角色
     */
    public static Integer getRole() {
        UserInfo userInfo = CONTEXT.get();
        return userInfo == null ? null : userInfo.getRole();
    }

    /**
     * 判断是否已登录
     */
    public static boolean isLogin() {
        return CONTEXT.get() != null;
    }

    /**
     * 清除当前用户信息（防止内存泄漏）
     */
    public static void clear() {
        CONTEXT.remove();
    }
}