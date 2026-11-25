/*
 * 阅读说明
 * ExternalGameLinkDTO的请求数据模型。
 * 字段上的 Bean Validation 注解在调用入口校验，权限、关联存在性与并发状态由 Service 核验。
 */
package com.catgal.server.domain.dto.admin;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ExternalGameLinkDTO {
    @NotNull @Min(0)
    private Long revision;
    @NotBlank @Size(max = 32)
    private String externalId;
}
