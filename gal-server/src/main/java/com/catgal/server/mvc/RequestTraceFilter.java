/*
 * 阅读说明
 * 把请求追踪号写入 MDC 和响应头，供统一响应与事务审计关联。
 */
package com.catgal.server.mvc;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import static com.catgal.common.constants.Constant.REQUEST_ID_HEADER;

/** 将响应、业务日志和事务审计关联到同一个有界请求编号，结束时清理复用线程上下文。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestTraceFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 优先接收代理使用的 X-Request-ID 追踪头，再兼容历史 requestId 头。
        // 仅接受有界字母数字、下划线和短横线，保证响应头、日志和审计可安全关联。
        String provided = request.getHeader("X-Request-ID");
        if (provided == null) provided = request.getHeader(REQUEST_ID_HEADER);
        String requestId = provided != null && provided.matches("[A-Za-z0-9_-]{1,64}") ? provided : UUID.randomUUID().toString();
        MDC.put(REQUEST_ID_HEADER, requestId);
        response.setHeader("X-Request-ID", requestId);
        try { chain.doFilter(request, response); }
        // Web 容器复用线程，正常返回和异常路径都清理本次追踪上下文。
        finally { MDC.remove(REQUEST_ID_HEADER); }
    }
}
