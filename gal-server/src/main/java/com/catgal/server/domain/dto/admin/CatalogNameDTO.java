/*
 * 阅读说明
 * 会社或标签名称的请求数据模型。
 * 字段上的 Bean Validation 注解在调用入口校验，权限、关联存在性与并发状态由 Service 核验。
 */
package com.catgal.server.domain.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "会社或标签名称")
public class CatalogNameDTO {
    @NotBlank
    @Size(max = 100)
    @Schema(description = "名称。会社最多100字符，标签最多50字符，保存时去除首尾空白")
    private String name;
}
