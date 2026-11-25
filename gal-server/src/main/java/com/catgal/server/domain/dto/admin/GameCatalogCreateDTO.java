/*
 * 阅读说明
 * 游戏基础资料与关联。创建后为草稿，发布人取当前管理员的请求数据模型。
 * 字段上的 Bean Validation 注解在调用入口校验，权限、关联存在性与并发状态由 Service 核验。
 */
package com.catgal.server.domain.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Data;

@Data
@Schema(description = "游戏基础资料与关联。创建后为草稿，发布人取当前管理员")
public class GameCatalogCreateDTO {
    @NotBlank
    @Size(max = 100)
    private String name;

    @Size(max = 500)
    @Schema(description = "HTTP或HTTPS封面地址，可为空")
    private String coverUrl;

    @Size(max = 12000)
    @Schema(description = "游戏介绍，最多12000字符")
    private String introduction;

    private LocalDate releaseDate;

    @NotNull
    @Size(max = 50)
    private Set<@NotNull @Positive Integer> tagIds = new LinkedHashSet<>();

    @NotNull
    @Size(max = 50)
    private Set<@NotNull @Positive Long> companyIds = new LinkedHashSet<>();
}
