package com.catgal.server.mvc;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
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



    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        log.info("=== LoginInterceptor 执行了 ===");
        log.info("请求路径: {}", request.getRequestURI());
        log.info("Authorization: {}", request.getHeader("Authorization"));
        // 1. 从请求头获取 token
        String token = request.getHeader("Authorization");
        
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        
        // 2. 验证 token
        if (token != null && JwtUtils.validateToken(token)) {
            // 3. 解析用户信息
            Long userId = JwtUtils.getUserId(token);
            String username = JwtUtils.getUsername(token);
            Integer role = JwtUtils.getRole(token);
            
            // 4. 存入 ThreadLocal
            UserInfo userInfo = UserInfo.builder()
                    .userId(userId)
                    .username(username)
                    .role(role)
                    .build();
            UserContext.setUser(userInfo);
            
            log.debug("用户已登录: {} ({})", username, userId);
        }
        
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 请求结束后清除 ThreadLocal，防止内存泄漏
        UserContext.clear();
    }
}