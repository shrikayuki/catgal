/*
 * 阅读说明
 * 管理员修改用户角色请求的请求数据模型。
 * 字段上的 Bean Validation 注解在调用入口校验，权限、关联存在性与并发状态由 Service 核验。
 */
package com.catgal.server.domain.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "管理员修改用户角色请求")
public class AdminRoleUpdateDTO {

    @NotNull(message = "角色不能为空")
    @Min(value = 0, message = "角色只能是0、1或2")
    @Max(value = 2, message = "角色只能是0、1或2")
    @Schema(description = "0普通用户、1创作者、2管理员", example = "1")
    private Integer role;

    @jakarta.validation.constraints.Pattern(regexp = "[0-9a-f]{64}")
    @Schema(description = "用户状态版本，新客户端提交以检测并发修改")
    private String stateVersion;
}
