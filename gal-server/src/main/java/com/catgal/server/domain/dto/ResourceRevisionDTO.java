package com.catgal.server.domain.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 编辑/送审/审核必须携带读取到的版本，避免并发页面覆盖新内容。 */
@Data
public class ResourceRevisionDTO {
    @NotNull @Min(0)
    private Long revision;
}
