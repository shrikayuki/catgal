/*
 * 阅读说明
 * 解析可选 Bearer Token 并读取数据库当前身份和有效账号状态。
 * 认证成功建立 UserContext，请求完成清理线程上下文。
 */
package com.catgal.server.mvc;

import com.catgal.common.context.UserContext;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.service.IAuthSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class LoginInterceptor implements HandlerInterceptor {

    private final IAuthSessionService sessions;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 防御性清理复用线程上的残留上下文；正常请求仍会在 afterCompletion 再清理一次。
        UserContext.clear();
        String authorization = request.getHeader("Authorization");
        if (authorization == null || authorization.isBlank()) {
            return true;
        }
        if (!authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            throw BusinessException.unauthorized("Authorization 请求头格式错误");
        }

        String token = authorization.substring(7).trim();
        // 先完成签名、账号和撤销检查，再建立仅用于当前请求的上下文。
        UserContext.setUser(sessions.authenticate(token));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 请求结束后清除 ThreadLocal，防止内存泄漏
        UserContext.clear();
    }
}
