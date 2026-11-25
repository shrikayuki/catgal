/*
 * 阅读说明
 * 分页查询用户、内容、资源审核及奖池操作记录的 HTTP 入口。
 * 各查询委托给管理审计或资源服务，保留各自唯一的历史来源。
 * 管理员访问由 /admin 拦截器链执行，复杂状态与事务规则位于被调用的 Service。
 */
package com.catgal.server.controller.admin;

import com.catgal.common.domain.R;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.server.domain.query.admin.AdminContentOperationLogQuery;
import com.catgal.server.domain.query.admin.AdminOperationLogQuery;
import com.catgal.server.domain.query.admin.AdminPrizeOperationLogQuery;
import com.catgal.server.domain.query.admin.AdminRedisOperationLogQuery;
import com.catgal.server.domain.query.admin.AdminResourceReviewLogQuery;
import com.catgal.server.domain.vo.admin.AdminOperationLogVO;
import com.catgal.server.service.IAdminOperationLogService;
import com.catgal.server.service.IResourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/operation-logs")
@RequiredArgsConstructor
@Tag(name = "后台管理 - 操作审计")
@SecurityRequirement(name = "bearerAuth")
public class AdminOperationLogController {
    private final IAdminOperationLogService auditService;
    private final IResourceService resourceService;

    @GetMapping
    @Operation(summary = "分页查询用户角色、封禁和解封操作记录")
    public R<PageDTO<AdminOperationLogVO>> page(@Valid AdminOperationLogQuery query) {
        return R.ok(auditService.pageUserOperations(query));
    }

    @GetMapping("/content")
    @Operation(summary = "分页查询评论和评价的隐藏、恢复记录")
    public R<PageDTO<AdminOperationLogVO>> pageContent(@Valid AdminContentOperationLogQuery query) {
        return R.ok(auditService.pageContentOperations(query));
    }

    @GetMapping("/resources")
    @Operation(summary = "分页查询资源通过、驳回与退回待审记录")
    public R<PageDTO<AdminOperationLogVO>> pageResources(@Valid AdminResourceReviewLogQuery query) {
        return R.ok(resourceService.pageAdminReviewOperations(query));
    }

    @GetMapping("/prizes")
    @Operation(summary = "分页查询奖品新增、编辑及启停记录")
    public R<PageDTO<AdminOperationLogVO>> pagePrizes(@Valid AdminPrizeOperationLogQuery query) {
        return R.ok(auditService.pagePrizeOperations(query));
    }

    @GetMapping("/redis")
    @Operation(summary = "分页查询 Redis 一致性修复与手动同步记录")
    public R<PageDTO<AdminOperationLogVO>> pageRedis(@Valid AdminRedisOperationLogQuery query) {
        return R.ok(auditService.pageRedisOperations(query));
    }
}
