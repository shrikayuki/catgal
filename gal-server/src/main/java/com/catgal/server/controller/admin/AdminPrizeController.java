/*
 * 阅读说明
 * 分页查询奖品、新增奖品、修改奖品的 HTTP 入口。
 * 路由前缀 /admin/prizes，请求绑定后委托 AdminPrizeService。
 * 管理员访问由 /admin 拦截器链执行，复杂状态与事务规则位于被调用的 Service。
 */
package com.catgal.server.controller.admin;

import com.catgal.common.domain.R;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.server.domain.dto.admin.AdminPrizeSaveDTO;
import com.catgal.server.domain.dto.admin.AdminStatusUpdateDTO;
import com.catgal.server.domain.po.PrizePool;
import com.catgal.server.domain.query.admin.AdminPageQuery;
import com.catgal.server.service.IAdminPrizeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

@RestController
@RequestMapping("/admin/prizes")
@RequiredArgsConstructor
@Tag(name = "后台管理 - 奖池")
@SecurityRequirement(name = "bearerAuth")
public class AdminPrizeController {

    private final IAdminPrizeService adminPrizeService;

    @GetMapping
    @Operation(summary = "分页查询奖品")
    public R<PageDTO<PrizePool>> pagePrizes(@Valid AdminPageQuery query) {
        return R.ok(adminPrizeService.pagePrizes(query));
    }

    @PostMapping
    @Operation(summary = "新增奖品")
    public R<PrizePool> createPrize(@Valid @RequestBody AdminPrizeSaveDTO dto) {
        return R.ok(adminPrizeService.createPrize(dto));
    }

    @PutMapping("/{prizeId}")
    @Operation(summary = "修改奖品", description = "不能通过修改接口变更奖品所属月份")
    public R<PrizePool> updatePrize(@PathVariable Long prizeId,
                                    @Valid @RequestBody AdminPrizeSaveDTO dto) {
        return R.ok(adminPrizeService.updatePrize(prizeId, dto));
    }

    @PatchMapping("/{prizeId}/status")
    @Operation(summary = "启用或停用奖品", description = "状态：0停用、1启用")
    public R<PrizePool> updateStatus(@PathVariable Long prizeId,
                                     @Valid @RequestBody AdminStatusUpdateDTO dto,
                                     @RequestHeader(value = "If-Match", required = false) String revision) {
        return R.ok(adminPrizeService.updateStatus(prizeId, dto.getStatus(), revision));
    }
}
