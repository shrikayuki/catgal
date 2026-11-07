package com.catgal.server.controller.admin;

import com.catgal.common.domain.R;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.server.domain.dto.admin.AdminRoleUpdateDTO;
import com.catgal.server.domain.query.admin.AdminPageQuery;
import com.catgal.server.domain.vo.admin.AdminUserVO;
import com.catgal.server.service.admin.AdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
@Tag(name = "后台管理 - 用户角色")
@SecurityRequirement(name = "bearerAuth")
public class AdminUserController {

    private final AdminUserService adminUserService;

    @GetMapping
    @Operation(summary = "分页查询用户（不返回密码）")
    public R<PageDTO<AdminUserVO>> pageUsers(@Valid AdminPageQuery query) {
        return R.ok(adminUserService.pageUsers(query));
    }

    @PatchMapping("/{userId}/role")
    @Operation(summary = "修改用户角色", description = "角色：0普通用户、1创作者、2管理员")
    public R<AdminUserVO> updateRole(@PathVariable Long userId,
                                     @Valid @RequestBody AdminRoleUpdateDTO dto) {
        return R.ok(adminUserService.updateRole(userId, dto.getRole()));
    }
}
