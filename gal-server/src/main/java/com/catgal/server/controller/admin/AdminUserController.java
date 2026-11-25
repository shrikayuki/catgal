/*
 * 阅读说明
 * 分页查询用户（不返回密码）、修改用户角色、查询用户当前状态和并发校验版本的 HTTP 入口。
 * 路由前缀 /admin/users，请求绑定后委托 IAdminUserService。
 * 管理员访问由 /admin 拦截器链执行，复杂状态与事务规则位于被调用的 Service。
 */
package com.catgal.server.controller.admin;

import com.catgal.common.domain.R;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.server.domain.dto.admin.AdminRoleUpdateDTO;
import com.catgal.server.domain.query.admin.AdminPageQuery;
import com.catgal.server.domain.vo.admin.AdminUserVO;
import com.catgal.server.service.IAdminUserService;
import com.catgal.server.domain.dto.admin.AdminUserStatusUpdateDTO;
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
@Tag(name = "后台管理 - 用户与账号状态")
@SecurityRequirement(name = "bearerAuth")
public class AdminUserController {

    private final IAdminUserService adminUserService;

    @GetMapping
    @Operation(summary = "分页查询用户（不返回密码）")
    public R<PageDTO<AdminUserVO>> pageUsers(@Valid AdminPageQuery query) {
        return R.ok(adminUserService.pageUsers(query));
    }

    @PatchMapping("/{userId}/role")
    @Operation(summary = "修改用户角色", description = "角色：0普通用户、1创作者、2管理员")
    public R<AdminUserVO> updateRole(@PathVariable Long userId,
                                     @Valid @RequestBody AdminRoleUpdateDTO dto) {
        return R.ok(adminUserService.updateRole(userId, dto.getRole(), dto.getStateVersion()));
    }
    @GetMapping("/{userId}")
    @Operation(summary = "查询用户当前状态和并发校验版本")
    public R<AdminUserVO> getUser(@PathVariable Long userId) { return R.ok(adminUserService.getUser(userId)); }

    @PatchMapping("/{userId}/status")
    @Operation(summary = "封禁或解封用户", description = "0封禁、1正常，期限携带时区，空期限封禁为永久，状态与审计同事务提交")
    public R<AdminUserVO> updateStatus(@PathVariable Long userId, @Valid @RequestBody AdminUserStatusUpdateDTO dto) {
        return R.ok(adminUserService.updateStatus(userId, dto));
    }

}
