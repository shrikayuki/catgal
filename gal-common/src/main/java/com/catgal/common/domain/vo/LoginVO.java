/*
 * 阅读说明
 * 登录响应的响应视图。
 * 业务服务将数据库记录及关联信息装配为此类型，再由 Controller 放入统一响应。
 */
package com.catgal.common.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "登录响应")
public class LoginVO {
    
    @Schema(description = "用户ID", example = "1")
    private Long userId;
    
    @Schema(description = "用户名", example = "玩家小明")
    private String username;
    
    @Schema(description = "用户身份", example = "0")
    private Integer role;
    
    @Schema(description = "JWT Token")
    private String token;

    @Schema(description = "公开头像读取地址")
    private String avatarUrl;
}
