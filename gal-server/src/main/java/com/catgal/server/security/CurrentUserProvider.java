package com.catgal.server.security;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 当前用户访问入口，隔离业务代码与 ThreadLocal 实现。
 */
@Component
public class CurrentUserProvider {

    public boolean isLoggedIn() {
        return UserContext.isLogin();
    }

    public UserInfo getOptionalUser() {
        return UserContext.getUser();
    }

    public Long getOptionalUserId() {
        return UserContext.getUserId();
    }

    public UserInfo requireUser() {
        UserInfo user = getOptionalUser();
        if (user == null || user.getUserId() == null) {
            throw BusinessException.unauthorized("用户未登录");
        }
        return user;
    }

    public Long requireUserId() {
        return requireUser().getUserId();
    }

    public Long requireSelf(Long requestedUserId) {
        if (requestedUserId == null) {
            throw BusinessException.badRequest("用户ID不能为空");
        }
        Long currentUserId = requireUserId();
        if (!Objects.equals(currentUserId, requestedUserId)) {
            throw BusinessException.forbidden("不能修改其他用户的信息");
        }
        return currentUserId;
    }
}
