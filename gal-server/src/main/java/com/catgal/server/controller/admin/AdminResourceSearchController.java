/*
 * 阅读说明
 * 重建资源搜索索引、查看资源索引积压及失败数量、安排失败资源同步重试的 HTTP 入口。
 * 路由前缀 /admin/search/resources，请求绑定后委托 ResourceSearchMaintenance、ResourceSearchOutbox。
 * 管理员访问由 /admin 拦截器链执行，复杂状态与事务规则位于被调用的 Service。
 */
package com.catgal.server.controller.admin;

import com.catgal.common.domain.R;
import com.catgal.server.search.ResourceSearchMaintenance;
import com.catgal.server.search.ResourceSearchOutbox;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/search/resources")
@RequiredArgsConstructor
@Tag(name = "后台管理 - 资源搜索索引")
@SecurityRequirement(name = "bearerAuth")
public class AdminResourceSearchController {
    private final ResourceSearchMaintenance maintenance;
    private final ResourceSearchOutbox outbox;

    @PostMapping("/rebuild")
    @Operation(summary = "重建资源搜索索引", description = "先执行V6迁移，分批导入全部审核状态的资源并切换别名。")
    public R<Map<String, Object>> rebuild() { return R.ok(maintenance.rebuild()); }

    @GetMapping("/outbox")
    @Operation(summary = "查看资源索引积压及失败数量")
    public R<Map<String, Object>> status() { return R.ok(outbox.status()); }

    @PostMapping("/retry")
    @Operation(summary = "安排失败资源同步重试")
    public R<Boolean> retry() {
        outbox.retryFailures();
        return R.ok(true);
    }
}
