/*
 * 阅读说明
 * 查看 Redis 同步积压与异常字面量 Key、比对单个游戏的 Redis、冗余字段和关系表实时统计、修复单个游戏统计并立即同步数据库的 HTTP 入口。
 * 路由前缀 /admin/redis-consistency，请求绑定后委托 RedisConsistencyAdminService。
 * 管理员访问由 /admin 拦截器链执行，复杂状态与事务规则位于被调用的 Service。
 */
package com.catgal.server.controller.admin;

import com.catgal.common.context.UserContext;
import com.catgal.common.domain.R;
import com.catgal.server.domain.vo.admin.GameStatsConsistencyVO;
import com.catgal.server.domain.vo.admin.RedisConsistencyOverviewVO;
import com.catgal.server.domain.vo.admin.ResourceDownloadConsistencyVO;
import com.catgal.server.service.IAdminOperationLogService;
import com.catgal.server.service.RedisConsistencyAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.catgal.server.domain.AdminRedisOperation.COUNTERS_SYNC;
import static com.catgal.server.domain.AdminRedisOperation.GAME_STATS_REPAIR;
import static com.catgal.server.domain.AdminRedisOperation.RESOURCE_DOWNLOAD_REPAIR;

@RestController
@RequestMapping("/admin/redis-consistency")
@RequiredArgsConstructor
@Tag(name = "后台管理 - Redis 一致性", description = "仅管理员可访问的积压检测、数据比对和修复接口")
@SecurityRequirement(name = "bearerAuth")
public class RedisConsistencyAdminController {

    private final RedisConsistencyAdminService adminService;
    private final IAdminOperationLogService auditService;

    @GetMapping("/overview")
    @Operation(summary = "查看 Redis 同步积压与异常字面量 Key")
    public R<RedisConsistencyOverviewVO> overview() {
        return R.ok(adminService.overview());
    }

    @GetMapping("/games/{gameId}")
    @Operation(summary = "比对单个游戏的 Redis、冗余字段和关系表实时统计")
    public R<GameStatsConsistencyVO> inspectGame(@PathVariable Long gameId) {
        return R.ok(adminService.inspectGame(gameId));
    }

    @PostMapping("/games/{gameId}/repair")
    @Operation(summary = "修复单个游戏统计并立即同步数据库")
    public R<GameStatsConsistencyVO> repairGame(@PathVariable Long gameId) {
        return R.ok(auditService.auditRedisMaintenance(UserContext.getUserId(), GAME_STATS_REPAIR,
                gameId, () -> adminService.repairGame(gameId)));
    }

    @GetMapping("/resources/{resourceId}")
    @Operation(summary = "比对单个资源的下载计数")
    public R<ResourceDownloadConsistencyVO> inspectResource(@PathVariable Long resourceId) {
        return R.ok(adminService.inspectResource(resourceId));
    }

    @PostMapping("/resources/{resourceId}/repair")
    @Operation(summary = "按 Redis/数据库较大值修复资源下载计数")
    public R<ResourceDownloadConsistencyVO> repairResource(@PathVariable Long resourceId) {
        return R.ok(auditService.auditRedisMaintenance(UserContext.getUserId(), RESOURCE_DOWNLOAD_REPAIR,
                resourceId, () -> adminService.repairResource(resourceId)));
    }

    @PostMapping("/sync")
    @Operation(summary = "立即消费一批游戏与资源下载统计积压")
    public R<RedisConsistencyOverviewVO> triggerSync() {
        return R.ok(auditService.auditRedisMaintenance(UserContext.getUserId(), COUNTERS_SYNC,
                null, adminService::triggerSync));
    }
}
