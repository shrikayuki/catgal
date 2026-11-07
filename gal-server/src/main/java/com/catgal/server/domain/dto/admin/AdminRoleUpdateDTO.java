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
}
