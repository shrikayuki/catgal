/*
 * 阅读说明
 * 登录请求参数的请求数据模型。
 * 字段上的 Bean Validation 注解在调用入口校验，权限、关联存在性与并发状态由 Service 核验。
 */
package com.catgal.common.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "登录请求参数")
public class LoginDTO {
    
    @Schema(description = "用户名", required = true, example = "玩家小明")
    private String username;
    
    @Schema(description = "密码", required = true, example = "123456")
    private String password;
}