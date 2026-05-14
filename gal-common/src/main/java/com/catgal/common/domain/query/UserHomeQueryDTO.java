package com.catgal.common.domain.query;

import com.catgal.common.enums.UserHomeTabEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "用户主页查询请求")
public class UserHomeQueryDTO extends PageQuery {

    /**
     * 评论
     */
    public static final Integer COMMENT = 1;

    /**
     * 评价
     */
    public static final Integer REVIEW = 2;

    /**
     * 收藏夹
     */
    public static final Integer FAVORITE = 3;

    /**
     * 发布资源
     */
    public static final Integer RESOURCE = 4;


    @Schema(description = "目标用户ID（不传则查当前登录用户）")
    @Min(1)
    private Integer userId;

    @Schema(description = "Tab类型：1-评论，2-评价，3-收藏夹，4-发布资源", example = "1")
    @NotNull(message = "Tab不能为空")
    @Min(1) @Max(4)
    private Integer tab = UserHomeTabEnum.COMMENT.getCode();
}