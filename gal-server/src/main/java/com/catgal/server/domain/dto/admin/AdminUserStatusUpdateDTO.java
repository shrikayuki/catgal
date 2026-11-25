/*
 * 阅读说明
 * 封禁或解封用户，期限必须携带时区，响应统一返回北京时间的请求数据模型。
 * 字段上的 Bean Validation 注解在调用入口校验，权限、关联存在性与并发状态由 Service 核验。
 */
package com.catgal.server.domain.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import lombok.Data;

@Data
@Schema(description = "封禁或解封用户，期限必须携带时区，响应统一返回北京时间")
public class AdminUserStatusUpdateDTO {
    /** 期望保存的原始状态，0 封禁、1 解封。 */
    @NotNull @Min(0) @Max(1)
    private Integer status;
    /** 每次人工变更的审计原因，解封时也要求填写。 */
    @NotBlank(message = "请填写操作原因") @Size(max = 255)
    private String reason;
    /** 带时区的未来期限，封禁时为空表示永久，解封时应为空。 */
    private OffsetDateTime bannedUntil;
    /** 回传最近一次用户详情的版本，冲突后先重新读取再由管理员确认。 */
    @NotBlank @Pattern(regexp = "[0-9a-f]{64}")
    private String stateVersion;
}
