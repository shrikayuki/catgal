/*
 * 阅读说明
 * 后台新增或修改奖品的请求数据模型。
 * 字段上的 Bean Validation 注解在调用入口校验，权限、关联存在性与并发状态由 Service 核验。
 */
package com.catgal.server.domain.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "后台新增或修改奖品")
public class AdminPrizeSaveDTO {

    /** ACTIVE 编辑时必填，字符串避免浏览器丢失 BIGINT 精度。新增奖品不需要。 */
    @Pattern(regexp = "0|[1-9][0-9]{0,18}", message = "奖品版本格式错误")
    private String revision;

    @NotBlank(message = "奖品名称不能为空")
    @Size(max = 100, message = "奖品名称最多100个字符")
    private String prizeName;

    @Size(max = 255, message = "奖品图片地址最多255个字符")
    private String prizeImage;

    @NotNull(message = "奖品类型不能为空")
    @Min(value = 1, message = "奖品类型只能是1到4")
    @Max(value = 4, message = "奖品类型只能是1到4")
    @Schema(description = "1萌萌点、2道具、3实物、4谢谢参与")
    private Integer prizeType;

    @NotBlank(message = "奖池月份不能为空")
    @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "奖池月份格式必须为yyyy-MM")
    private String month;

    @NotNull(message = "权重不能为空")
    @Min(value = 0, message = "权重不能小于0")
    private Integer weight;

    @NotNull(message = "总库存不能为空")
    @Min(value = 0, message = "总库存不能小于0")
    @Schema(description = "0表示无限库存")
    private Integer totalStock;

    @NotNull(message = "剩余库存不能为空")
    @Min(value = 0, message = "剩余库存不能小于0")
    private Integer remainStock;

    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态只能是0或1")
    @Max(value = 1, message = "状态只能是0或1")
    private Integer status;
}
