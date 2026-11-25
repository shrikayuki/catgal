/*
 * 阅读说明
 * 管理员修改业务状态请求的请求数据模型。
 * 字段上的 Bean Validation 注解在调用入口校验，权限、关联存在性与并发状态由 Service 核验。
 */
package com.catgal.server.domain.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "管理员修改业务状态请求")
public class AdminStatusUpdateDTO {

    @NotNull(message = "状态不能为空")
    @Schema(description = "目标状态，具体取值以接口说明为准", example = "1")
    private Integer status;
}
