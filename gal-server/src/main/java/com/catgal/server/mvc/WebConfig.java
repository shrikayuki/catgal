/*
 * 阅读说明
 * 注册 MVC 拦截器，按登录解析、必需登录和管理员授权建立请求链。
 */
package com.catgal.server.mvc;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final LoginInterceptor loginInterceptor;
    private final LoginRequiredInterceptor loginRequiredInterceptor;
    private final AdminInterceptor adminInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 先解析可选凭据建立身份，再检查必需登录标记，最后核验管理路径权限。
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**")  // 拦截所有请求
                .excludePathPatterns(
                        "/user/login",
                        "/user/register",
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/doc.html",
                        "/webjars/**"
                )
                .order(0);
        registry.addInterceptor(loginRequiredInterceptor)
                .addPathPatterns("/**")
                .order(1);
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/admin/**")
                .order(2);
    }
}
