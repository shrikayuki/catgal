/*
 * 阅读说明
 * 重建游戏搜索索引、查询待同步数量、失败数量和最早积压时间、立即安排失败事件重试的 HTTP 入口。
 * 路由前缀 /admin/search/games，请求绑定后委托 GameSearchMaintenance、SearchOutbox。
 * 管理员访问由 /admin 拦截器链执行，复杂状态与事务规则位于被调用的 Service。
 */
package com.catgal.server.controller.admin;

import com.catgal.common.domain.R;
import com.catgal.server.search.GameSearchMaintenance;
import com.catgal.server.search.SearchOutbox;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/search/games")
@Tag(name = "后台管理 - 游戏搜索索引")
@SecurityRequirement(name = "bearerAuth")
public class AdminSearchController {
    private final GameSearchMaintenance maintenance;
    private final SearchOutbox outbox;

    public AdminSearchController(GameSearchMaintenance maintenance, @Qualifier("searchOutbox") SearchOutbox outbox) {
        this.maintenance = maintenance;
        this.outbox = outbox;
    }

    @PostMapping("/rebuild")
    @Operation(summary = "重建游戏搜索索引", description = "同步执行分批导入和别名切换，需要先执行搜索迁移SQL。大数据量时须调整客户端等待时间。")
    public R<Map<String, Object>> rebuild() {
        return R.ok(maintenance.rebuild());
    }

    @GetMapping("/outbox")
    @Operation(summary = "查询待同步数量、失败数量和最早积压时间")
    public R<Map<String, Object>> status() {
        return R.ok(outbox.status());
    }

    @PostMapping("/retry")
    @Operation(summary = "立即安排失败事件重试")
    public R<Boolean> retry() {
        outbox.retryFailures();
        return R.ok(true);
    }
}
