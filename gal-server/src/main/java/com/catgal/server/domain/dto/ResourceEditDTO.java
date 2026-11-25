package com.catgal.server.domain.dto;

import com.catgal.common.domain.dto.ResourceAddDTO;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 复用发布字段校验，gameId 只用于核对原游戏，编辑时禁止转移归属。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ResourceEditDTO extends ResourceAddDTO {
    @NotNull @Min(0)
    private Long revision;
}
