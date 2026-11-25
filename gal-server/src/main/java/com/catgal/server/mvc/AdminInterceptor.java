/*
 * 阅读说明
 * /admin 路径的实时管理员授权，使用数据库当前角色防止旧 Token 权限滞留。
 */
package com.catgal.server.mvc;

import com.catgal.common.context.UserContext;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.User;
import com.catgal.server.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Objects;

import static com.catgal.common.constants.UserRoleConstant.ADMIN;

@Component
@RequiredArgsConstructor
public class AdminInterceptor implements HandlerInterceptor {

    private final UserMapper userMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!UserContext.isLogin()) {
            throw BusinessException.unauthorized("管理员接口需要登录");
        }
        User currentUser = userMapper.selectById(UserContext.getUserId());
        if (currentUser == null || !Objects.equals(currentUser.getRole(), ADMIN)) {
            throw BusinessException.forbidden("仅管理员可访问该接口");
        }
        return true;
    }
}
