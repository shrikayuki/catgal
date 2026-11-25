/*
 * 阅读说明
 * AiVectorCatalog的 HTTP 入口。
 * 路由前缀 见方法上的 Mapping 注解，请求绑定后委托 AiVectorCatalogService。
 * 参数注解决定路径、查询或正文绑定方式，业务可见性与数据组装在服务层核验。
 */
package com.catgal.server.controller;

import com.catgal.common.domain.R;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.ai.tools.AiToolAuthenticationFilter;
import com.catgal.server.ai.vector.AiVectorCatalogRequests;
import com.catgal.server.ai.vector.AiVectorCatalogService;
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
@RequestMapping(value = "/internal/ai/vector-catalog", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AiVectorCatalogController {
    private final AiVectorCatalogService catalog;

    @PostMapping("/pending")
    public R<AiVectorCatalogService.Pending> pending(@Valid @RequestBody AiVectorCatalogRequests.Pending body,
                                                   HttpServletRequest request) {
        requireService(request);
        return R.ok(catalog.pending(body.resultLimit()));
    }

    @PostMapping("/export")
    public R<AiVectorCatalogService.Export> export(@Valid @RequestBody AiVectorCatalogRequests.Export body,
                                                 HttpServletRequest request) {
        requireService(request);
        return R.ok(catalog.export(body.gameIds()));
    }

    @PostMapping("/ack")
    public R<AiVectorCatalogService.Acknowledged> acknowledge(@Valid @RequestBody AiVectorCatalogRequests.Batch body,
                                                            HttpServletRequest request) {
        requireService(request);
        return R.ok(catalog.acknowledge(body.items()));
    }

    @PostMapping("/failed")
    public R<AiVectorCatalogService.Failed> failed(@Valid @RequestBody AiVectorCatalogRequests.Failed body,
                                                 HttpServletRequest request) {
        requireService(request);
        return R.ok(catalog.failed(body.items(), body.errorCode()));
    }

    @PostMapping("/rebuild")
    public R<AiVectorCatalogService.Rebuild> rebuild(@RequestBody AiVectorCatalogRequests.Empty body,
                                                   HttpServletRequest request) {
        requireService(request);
        return R.ok(catalog.rebuild());
    }

    @PostMapping("/status")
    public R<AiVectorCatalogService.Status> status(@RequestBody AiVectorCatalogRequests.Empty body,
                                                 HttpServletRequest request) {
        requireService(request);
        return R.ok(catalog.status());
    }

    private void requireService(HttpServletRequest request) {
        if (!Boolean.TRUE.equals(request.getAttribute(AiToolAuthenticationFilter.SERVICE_ATTRIBUTE)))
            throw BusinessException.unauthorized("AI目录服务身份无效");
    }
}
