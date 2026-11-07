package com.catgal.server.mvc;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.JwtUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
@RequiredArgsConstructor
public class LoginInterceptor implements HandlerInterceptor {

    private final JwtUtils jwtUtils;

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
        if (!jwtUtils.validateToken(token)) {
            throw BusinessException.unauthorized("登录凭证无效或已过期");
        }

        try {
            Long userId = jwtUtils.getUserId(token);
            String username = jwtUtils.getUsername(token);
            Integer role = jwtUtils.getRole(token);
            if (userId == null) {
                throw BusinessException.unauthorized("登录凭证缺少用户信息");
            }
            UserInfo userInfo = UserInfo.builder()
                    .userId(userId)
                    .username(username)
                    .role(role)
                    .build();
            UserContext.setUser(userInfo);
            log.debug("用户已登录: {} ({})", username, userId);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("解析登录凭证失败: path={}", request.getRequestURI());
            throw BusinessException.unauthorized("登录凭证无效或已过期");
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 请求结束后清除 ThreadLocal，防止内存泄漏
        UserContext.clear();
    }
}
