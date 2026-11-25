/*
 * 阅读说明
 * 用户id的响应视图。
 * 业务服务将数据库记录及关联信息装配为此类型，再由 Controller 放入统一响应。
 */
package com.catgal.common.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class UserFansOrFollowsVO {

    @Schema(description = "用户id")
    private Long userId;
    @Schema(description = "用户名称")
    private String username;
    @Schema(description = "用户头像")
    private String avatarUrl;
    @Schema(description = "用户签名")
    private String signature;
    @Schema(description = "是否关注,前端显示有些差异")
    private Boolean isFollow;
}
