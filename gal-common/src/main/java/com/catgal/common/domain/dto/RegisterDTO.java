/*
 * 阅读说明
 * 注册请求参数的请求数据模型。
 * 字段上的 Bean Validation 注解在调用入口校验，权限、关联存在性与并发状态由 Service 核验。
 */
package com.catgal.common.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "注册请求参数")
public class RegisterDTO {
    
    @Schema(description = "用户名（3-17字符）", required = true, example = "新玩家", minLength = 3, maxLength = 17)
    private String username;
    
    @Schema(description = "密码（至少6位）", required = true, example = "123456", minLength = 6)
    private String password;
    
    @Schema(description = "手机号", example = "13912345678")
    private String phone;
    
    @Schema(description = "个人签名", example = "萌新报道", maxLength = 107)
    private String signature;
}