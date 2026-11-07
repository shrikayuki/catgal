package com.catgal.server.controller.admin;

import com.catgal.common.domain.R;
import com.catgal.server.domain.vo.admin.AdminDashboardVO;
import com.catgal.server.service.admin.AdminDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/dashboard")
@RequiredArgsConstructor
@Tag(name = "后台管理 - 仪表盘")
@SecurityRequirement(name = "bearerAuth")
public class AdminDashboardController {

    private final AdminDashboardService adminDashboardService;

    @GetMapping
    @Operation(summary = "查看后台运营概览")
    public R<AdminDashboardVO> overview() {
        return R.ok(adminDashboardService.overview());
    }
}
