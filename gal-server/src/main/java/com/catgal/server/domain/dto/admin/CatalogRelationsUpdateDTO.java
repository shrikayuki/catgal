/*
 * 阅读说明
 * 完整替换某类关联，空集合表示解绑全部。标签ID范围为正整数的请求数据模型。
 * 字段上的 Bean Validation 注解在调用入口校验，权限、关联存在性与并发状态由 Service 核验。
 */
package com.catgal.server.domain.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Set;
import lombok.Data;

@Data
@Schema(description = "完整替换某类关联，空集合表示解绑全部。标签ID范围为正整数")
public class CatalogRelationsUpdateDTO {
    @NotNull
    @Min(0)
    private Long revision;

    @NotNull
    @Size(max = 50)
    private Set<@NotNull @Positive Long> ids;
}
