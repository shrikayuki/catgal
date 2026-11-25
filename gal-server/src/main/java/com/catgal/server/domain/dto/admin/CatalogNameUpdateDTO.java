/*
 * 阅读说明
 * 读取资料时返回的revision，并发修改后需刷新再提交的请求数据模型。
 * 字段上的 Bean Validation 注解在调用入口校验，权限、关联存在性与并发状态由 Service 核验。
 */
package com.catgal.server.domain.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class CatalogNameUpdateDTO extends CatalogNameDTO {
    @NotNull
    @Min(0)
    @Schema(description = "读取资料时返回的revision，并发修改后需刷新再提交")
    private Long revision;
}
