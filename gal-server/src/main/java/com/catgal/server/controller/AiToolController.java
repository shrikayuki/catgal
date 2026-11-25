/*
 * 阅读说明
 * AiTool的 HTTP 入口。
 * 路由前缀 见方法上的 Mapping 注解，请求绑定后委托 AiCatalogToolService。
 * 参数注解决定路径、查询或正文绑定方式，业务可见性与数据组装在服务层核验。
 */
package com.catgal.server.controller;

import com.catgal.common.domain.R;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.ai.tools.AiCatalogToolService;
import com.catgal.server.ai.tools.AiToolAuthenticationFilter;
import com.catgal.server.ai.tools.AiToolGame;
import com.catgal.server.ai.tools.AiToolRequests;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequestMapping(value = "/internal/ai/tools", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AiToolController {
    private final AiCatalogToolService tools;

    @PostMapping("/search-games")
    public R<AiCatalogToolService.SearchResult> search(@Valid @RequestBody AiToolRequests.Search body,
                                                     HttpServletRequest request) {
        requireTrustedIdentity(request, body.userId());
        return R.ok(tools.search(body));
    }

    @PostMapping("/game-detail")
    public R<AiToolGame> detail(@Valid @RequestBody AiToolRequests.Detail body, HttpServletRequest request) {
        requireTrustedIdentity(request, body.userId());
        return R.ok(tools.detail(body));
    }

    @PostMapping("/my-interests")
    public R<AiCatalogToolService.InterestResult> interests(@Valid @RequestBody AiToolRequests.Interests body,
                                                          HttpServletRequest request) {
        requireTrustedIdentity(request, body.userId());
        return R.ok(tools.interests(body));
    }

    @PostMapping("/validate-games")
    public R<AiCatalogToolService.ValidationResult> validate(@Valid @RequestBody AiToolRequests.Validate body,
                                                           HttpServletRequest request) {
        requireTrustedIdentity(request, body.userId());
        return R.ok(tools.validate(body));
    }

    private void requireTrustedIdentity(HttpServletRequest request, Long userId) {
        if (!userId.equals(request.getAttribute(AiToolAuthenticationFilter.USER_ATTRIBUTE)))
            throw BusinessException.unauthorized("AI工具用户上下文无效");
    }
}
