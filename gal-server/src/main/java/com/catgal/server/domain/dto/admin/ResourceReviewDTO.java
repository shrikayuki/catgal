package com.catgal.server.domain.dto.admin;

import com.catgal.server.domain.dto.ResourceRevisionDTO;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** status=0 时 Service 要求填写原因，原因随审核版本保存。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ResourceReviewDTO extends ResourceRevisionDTO {
    @NotNull @Min(0) @Max(2)
    private Integer status;
    @Size(max = 500)
    private String reason;
}
