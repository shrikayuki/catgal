package com.catgal.server.mvc;

import com.catgal.server.mvc.annotation.LoginRequired;
import com.catgal.server.security.CurrentUserProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class LoginRequiredInterceptor implements HandlerInterceptor {

    private final CurrentUserProvider currentUserProvider;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        boolean required = handlerMethod.hasMethodAnnotation(LoginRequired.class)
                || AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), LoginRequired.class);
        if (required) {
            currentUserProvider.requireUserId();
        }
        return true;
    }
}
